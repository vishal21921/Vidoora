package com.example.vidoora.core.work

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.data.repository.UploadRepositoryImpl
import com.example.vidoora.core.database.dao.*
import com.example.vidoora.core.database.entity.UploadTaskEntity
import com.example.vidoora.core.di.StorageHttpClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val taskDao: UploadTaskDao,
    private val partDao: UploadPartDao,
    private val userDao: UserDao,
    private val repository: UploadRepositoryImpl,
    @StorageHttpClient private val storageClient: HttpClient
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("KEY_TASK_ID") ?: return@withContext Result.failure()
        val task = taskDao.getTaskById(id) ?: return@withContext Result.failure()
        if (!MultipartPlan.runnable(task.status)) return@withContext Result.success()
        if (!isOwner(task)) return@withContext Result.failure()
        try {
            if(task.status=="DELETING") {
                return@withContext if(repository.performDeletion(id) is Resource.Success) Result.success() else Result.retry()
            }
            if (task.status == "CANCELLING") {
                val result = repository.performCancellation(id)
                return@withContext if (result is Resource.Success) Result.success() else cancellationRetry(id, result)
            }
            setForeground(createForegroundInfo(task, task.progressPercentage))
            if (task.status != "COMPLETING") {
                if (taskDao.transition(id, listOf("QUEUED", "UPLOADING", "RETRYING"), "UPLOADING") == 0) return@withContext Result.success()
                val file = File(task.sourceUri)
                require(file.isFile && file.length() == task.fileSizeBytes) { "Upload draft is missing or has changed; select the video again" }
                val pending = partDao.getUnfinishedPartsForTask(id)
                if (pending.isNotEmpty()) {
                    checkRunnable(task)
                    when (val refresh = repository.refreshPartUrls(id, task.videoId, task.uploadSessionId, pending.map { it.partNumber })) {
                        is Resource.Error -> return@withContext failedRequest(task, refresh)
                        else -> Unit
                    }
                }
                RandomAccessFile(file, "r").use { source ->
                    for (part in partDao.getUnfinishedPartsForTask(id)) {
                        checkRunnable(task)
                        val chunk = ByteArray(part.sizeBytes.toInt())
                        require(part.startByte >= 0 && part.sizeBytes > 0 && part.startByte + part.sizeBytes <= task.fileSizeBytes)
                        source.seek(part.startByte)
                        source.readFully(chunk)
                        // Retrying this same immutable byte range with the same part number replaces
                        // a part whose successful provider response may have been lost before Room saved it.
                        val response = storageClient.put(part.uploadUrl) { setBody(ByteArrayContent(chunk, ContentType.Application.OctetStream)) }
                        if (response.status.value !in 200..299) {
                            partDao.updatePart(part.copy(retryCount = part.retryCount + 1))
                            return@withContext failedRequest(task, Resource.Error("Storage rejected part ${part.partNumber}", response.status.value.toString()), storage = true)
                        }
                        val eTag = MultipartPlan.eTag(response.headers[HttpHeaders.ETag])
                        checkRunnable(task)
                        partDao.updatePart(part.copy(status = "SUCCESS", eTag = eTag))
                        val all = partDao.getPartsForTask(id)
                        val done = all.filter { it.status == "SUCCESS" }
                        val percentage = (done.sumOf { it.sizeBytes } * 100 / task.fileSizeBytes).toInt()
                        taskDao.updateProgress(id, percentage, done.size)
                        setForeground(createForegroundInfo(task, percentage))
                    }
                }
                checkRunnable(task)
                if (taskDao.transition(id, listOf("UPLOADING"), "COMPLETING") == 0) return@withContext Result.success()
            }
            if (!isOwner(task)) return@withContext Result.failure()
            when (val complete = repository.completeUploadTask(id, task.videoId, task.uploadSessionId)) {
                is Resource.Success -> Result.success()
                is Resource.Error -> failedRequest(task, complete)
                else -> Result.retry()
            }
        } catch (e: CancellationException) {
            // WorkManager stop, pause, logout and cancellation must preserve the state
            // already committed by the caller. Never turn a cancellation into FAILED.
            throw e
        } catch (e: IllegalArgumentException) {
            taskDao.transition(id, listOf("UPLOADING", "QUEUED", "RETRYING", "COMPLETING"), "FAILED", e.message)
            Result.failure()
        } catch (e: Exception) {
            failedRequest(task, Resource.Error(e.localizedMessage ?: "Upload temporarily unavailable"))
        }
    }
    private suspend fun isOwner(task: UploadTaskEntity) = task.userId.isNotBlank() && userDao.getUserOnce()?.id == task.userId
    private suspend fun checkRunnable(task: UploadTaskEntity) {
        currentCoroutineContext().ensureActive()
        if (isStopped || !isOwner(task) || taskDao.getTaskById(task.id)?.status != "UPLOADING") throw CancellationException("Upload stopped")
    }
    private suspend fun cancellationRetry(id: String, result: Resource<Unit>): Result {
        val message = (result as? Resource.Error)?.message ?: "Cancellation awaiting backend"
        taskDao.transition(id, listOf("CANCELLING"), "CANCELLING", message)
        // Preserve CANCELLING when retries exhaust so the user can retry rather than reupload.
        return if (runAttemptCount < 6) Result.retry() else Result.failure()
    }
    private suspend fun failedRequest(task: UploadTaskEntity, error: Resource.Error, storage: Boolean = false): Result {
        val status = error.code?.toIntOrNull()
        val retry = runAttemptCount < 6 && (status == null || MultipartPlan.retryable(status) || (storage && status in setOf(401, 403)))
        val current = taskDao.getTaskById(task.id)?.status
        val next = if (!retry) "FAILED" else if (current == "COMPLETING") "COMPLETING" else "RETRYING"
        val changed = taskDao.transition(task.id, listOf("QUEUED", "UPLOADING", "RETRYING", "COMPLETING"), next, error.message)
        return if (changed == 0) Result.success() else if (retry) Result.retry() else Result.failure()
    }
    private fun createForegroundInfo(task: UploadTaskEntity, progress: Int): ForegroundInfo {
        val channelId = "vidoora_upload_channel"
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channelId, "Video uploads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setContentTitle("Uploading: ${task.title}").setContentText("$progress% uploaded")
            .setSmallIcon(R.drawable.stat_sys_upload).setProgress(100, progress, false).setOngoing(true).build()
        val notificationId = task.id.hashCode() and Int.MAX_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }
}
