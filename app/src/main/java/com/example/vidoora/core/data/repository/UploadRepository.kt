package com.example.vidoora.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.database.AppDatabase
import com.example.vidoora.core.database.dao.*
import com.example.vidoora.core.database.entity.*
import com.example.vidoora.core.network.dto.*
import com.example.vidoora.core.work.MultipartPlan
import com.example.vidoora.core.work.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
interface UploadRepository {
    fun getAllTasksFlow(): Flow<List<UploadTaskEntity>>
    suspend fun getTaskById(taskId: String): UploadTaskEntity?
    suspend fun initiateAndQueueUpload(
        sourceUri: String,
        title: String,
        description: String?,
        visibility: String,
        fileSizeBytes: Long,
        mimeType: String
    ): Resource<String>

    suspend fun refreshPartUrls(taskId: String, videoId: String, uploadSessionId: String, partNumbers: List<Int>): Resource<Unit>
    suspend fun completeUploadTask(taskId: String, videoId: String, uploadSessionId: String): Resource<CompleteUploadResponseDto>
    suspend fun abortUploadTask(taskId: String, videoId: String, uploadSessionId: String): Resource<Unit>
    suspend fun pauseUploadTask(taskId: String)
    suspend fun resumeUploadTask(taskId: String)
    suspend fun retryUploadTask(taskId: String)
    fun enqueueWorkManagerTask(taskId: String)
    suspend fun deleteQueueVideo(taskId: String): Resource<Unit>
    suspend fun deleteQueuedVideoIfPresent(videoId: String): Resource<Unit>?
    suspend fun removeDeletedVideo(videoId: String)
}

@Singleton
class UploadRepositoryImpl @Inject constructor(
    private val httpClient: HttpClient,
    private val taskDao: UploadTaskDao,
    private val partDao: UploadPartDao,
    private val userDao: UserDao,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context
) : UploadRepository {
    private val manager get() = WorkManager.getInstance(context)
    override fun getAllTasksFlow(): Flow<List<UploadTaskEntity>> = combine(taskDao.getAllTasksFlow(), userDao.getUser()) { tasks, user -> tasks.filter { it.userId == user?.id } }
    override suspend fun getTaskById(taskId: String): UploadTaskEntity? = taskDao.getTaskById(taskId)?.takeIf { it.userId.isNotBlank() && it.userId == userDao.getUserOnce()?.id }
    private suspend fun ownedTask(taskId: String): UploadTaskEntity = getTaskById(taskId) ?: error("Upload belongs to another account or has no owner")

    override suspend fun deleteQueuedVideoIfPresent(videoId: String): Resource<Unit>? {
        val owner=userDao.getUserOnce()?.id ?: return null
        val task=taskDao.getAllTasksOnce().firstOrNull { it.userId==owner && it.videoId==videoId } ?: return null
        return deleteQueueVideo(task.id)
    }

    override suspend fun deleteQueueVideo(taskId: String): Resource<Unit> = api {
        ownedTask(taskId)
        taskDao.transition(taskId, listOf("QUEUED","UPLOADING","RETRYING","PAUSED","FAILED","CANCELLING","COMPLETING","UPLOADED","CANCELLED","DELETING"), "DELETING")
        withContext(Dispatchers.IO) { manager.cancelUniqueWork("upload_task_$taskId").result.get() }
        val result=performDeletion(taskId)
        if(result is Resource.Error) enqueueWorkManagerTask(taskId)
        result
    }

    /** Durable intent is stored before stopping work. A failed request stays visible and retries online. */
    suspend fun performDeletion(taskId: String): Resource<Unit> = api {
        val task=ownedTask(taskId)
        check(task.status=="DELETING")
        check(userDao.getUserOnce()?.id==task.userId)
        var response=httpClient.delete("api/v1/videos/${task.videoId}")
        if(response.status==HttpStatusCode.Conflict) {
            val abort=httpClient.post("api/v1/videos/${task.videoId}/abort-upload") { setBody(AbortUploadRequestDto(task.uploadSessionId)) }
            // Completion may win the race with abort; retry owner deletion either way.
            if(abort.status.value in setOf(401,403)) return@api Resource.Error("Sign in again to delete this upload")
            if(abort.status!=HttpStatusCode.OK) {
                // A completion response may have been lost after R2 assembled the object.
                val parts=partDao.getPartsForTask(taskId).sortedBy { it.partNumber }
                if(parts.size==task.totalParts && parts.all { it.status=="SUCCESS" && !it.eTag.isNullOrBlank() }) {
                    httpClient.post("api/v1/videos/${task.videoId}/complete-upload") {
                        setBody(CompleteUploadRequestDto(task.uploadSessionId,parts.map { PartTagDto(it.partNumber,MultipartPlan.eTag(it.eTag)) }))
                    }
                }
            }
            response=httpClient.delete("api/v1/videos/${task.videoId}")
        }
        if(response.status!=HttpStatusCode.OK) {
            taskDao.transition(taskId,listOf("DELETING"),"DELETING","Deletion pending. Connect and retry.")
            Resource.Error("Could not confirm deletion. It will retry when connected.")
        } else {
            val envelope=response.body<ApiEnvelope<DeletionAcknowledgement>>()
            check(envelope.success && envelope.data?.accepted==true)
            cleanupDraft(task)
            cleanupPreview(task)
            taskDao.deleteTaskById(task.id)
            Resource.Success(Unit)
        }
    }

    override suspend fun removeDeletedVideo(videoId: String) {
        val owner=userDao.getUserOnce()?.id ?: return
        taskDao.getAllTasksOnce().filter { it.userId==owner && it.videoId==videoId }.forEach { task ->
            withContext(Dispatchers.IO) { manager.cancelUniqueWork("upload_task_${task.id}").result.get() }
            cleanupDraft(task)
            cleanupPreview(task)
            taskDao.deleteTaskById(task.id) // Parts cascade; original media is never deleted.
        }
    }

    override suspend fun initiateAndQueueUpload(sourceUri: String, title: String, description: String?, visibility: String, fileSizeBytes: Long, mimeType: String): Resource<String> = withContext(Dispatchers.IO) {
        if (fileSizeBytes !in 1..MultipartPlan.MAX_BYTES) return@withContext Resource.Error("File must be between 1 byte and 1 GiB")
        val owner = userDao.getUserOnce()?.id ?: return@withContext Resource.Error("Sign in before uploading")
        val taskId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "upload_drafts")
        val draft = File(dir, "$taskId.draft")
        var persisted = false
        var remote: InitiateUploadResponseDto? = null
        try {
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create upload draft directory" }
            check(dir.usableSpace > fileSizeBytes + 16 * 1024 * 1024L) { "Not enough free storage to prepare the video" }
            context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                FileOutputStream(draft).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MultipartPlan.MAX_BYTES) { "Video exceeds 1 GiB" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: error("Unable to open selected video")
            val bytes = draft.length()
            require(bytes in 1..MultipartPlan.MAX_BYTES) { "Selected video is empty or too large" }
            check(userDao.getUserOnce()?.id == owner) { "Account changed while preparing the upload" }
            val response = httpClient.post("api/v1/videos/initiate-upload") { setBody(InitiateUploadRequestDto(title.trim(), description?.trim(), bytes, mimeType.trim().lowercase(), visibility)) }
            val envelope = response.body<ApiEnvelope<InitiateUploadResponseDto>>()
            val data = envelope.data
            if (response.status != HttpStatusCode.Created || !envelope.success || data == null) return@withContext Resource.Error(envelope.error?.message ?: "Upload initiation failed", response.status.value.toString())
            remote = data
            val ranges = MultipartPlan.ranges(bytes, data.chunkSizeBytes)
            require(data.totalParts == ranges.size && data.parts.map { it.partNumber }.sorted() == ranges.map { it.partNumber }) { "Invalid multipart plan from backend" }
            check(userDao.getUserOnce()?.id == owner) { "Account changed while initiating upload" }
            val urls = data.parts.associateBy { it.partNumber }
            database.withTransaction {
                taskDao.insertTask(UploadTaskEntity(taskId, data.videoId, data.uploadSessionId, draft.absolutePath, bytes, mimeType, title, description, visibility, "QUEUED", totalParts = ranges.size, userId = owner))
                partDao.insertParts(ranges.map { UploadPartEntity(taskId, it.partNumber, urls.getValue(it.partNumber).uploadUrl, it.startByte, it.endByte, it.sizeBytes, "PENDING") })
            }
            persisted = true
            enqueueWorkManagerTask(taskId)
            Resource.Success(taskId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Resource.Error(e.localizedMessage ?: "Unable to prepare upload") }
        finally {
            if (!persisted) {
                draft.delete()
                val session = remote
                if (session != null && userDao.getUserOnce()?.id == owner) withContext(NonCancellable) {
                    try { httpClient.post("api/v1/videos/${session.videoId}/abort-upload") { setBody(AbortUploadRequestDto(session.uploadSessionId)) } }
                    catch (_: Exception) { /* server expiry and bucket lifecycle clean up lost initiation responses */ }
                }
            }
        }
    }

    override suspend fun refreshPartUrls(taskId: String, videoId: String, uploadSessionId: String, partNumbers: List<Int>): Resource<Unit> = api {
        val task = ownedTask(taskId)
        require(task.videoId == videoId && task.uploadSessionId == uploadSessionId)
        val response = httpClient.post("api/v1/videos/$videoId/upload-parts/refresh") { setBody(RefreshPartsRequestDto(uploadSessionId, partNumbers)) }
        val envelope = response.body<ApiEnvelope<RefreshPartsResponseDto>>()
        if (response.status != HttpStatusCode.OK || !envelope.success || envelope.data == null) Resource.Error(envelope.error?.message ?: "URL refresh failed", response.status.value.toString())
        else {
            require(envelope.data.refreshedParts.map { it.partNumber }.sorted() == partNumbers.sorted())
            database.withTransaction { envelope.data.refreshedParts.forEach { partDao.updatePartUrl(taskId, it.partNumber, it.uploadUrl) } }
            Resource.Success(Unit)
        }
    }

    override suspend fun completeUploadTask(taskId: String, videoId: String, uploadSessionId: String): Resource<CompleteUploadResponseDto> = api {
        val task = ownedTask(taskId)
        require(task.videoId == videoId && task.uploadSessionId == uploadSessionId && task.status == "COMPLETING")
        val parts = partDao.getPartsForTask(taskId).sortedBy { it.partNumber }
        require(parts.size == task.totalParts && parts.all { it.status == "SUCCESS" && !it.eTag.isNullOrBlank() }) { "Every part must have a storage ETag" }
        val response = httpClient.post("api/v1/videos/$videoId/complete-upload") { setBody(CompleteUploadRequestDto(uploadSessionId, parts.map { PartTagDto(it.partNumber, MultipartPlan.eTag(it.eTag)) })) }
        val envelope = response.body<ApiEnvelope<CompleteUploadResponseDto>>()
        if (response.status != HttpStatusCode.OK || !envelope.success || envelope.data == null) Resource.Error(envelope.error?.message ?: "Upload completion failed", response.status.value.toString())
        else {
            taskDao.transition(taskId, listOf("COMPLETING", "CANCELLING"), "UPLOADED")
            cleanupDraft(task)
            Resource.Success(envelope.data)
        }
    }

    override suspend fun abortUploadTask(taskId: String, videoId: String, uploadSessionId: String): Resource<Unit> = api {
        val task = ownedTask(taskId)
        require(task.videoId == videoId && task.uploadSessionId == uploadSessionId)
        check(task.status !in setOf("UPLOADED", "COMPLETING")) { "Upload completion is in progress or already finished" }
        if (task.status == "CANCELLED") return@api Resource.Success(Unit)
        taskDao.transition(taskId, listOf("QUEUED", "UPLOADING", "RETRYING", "PAUSED", "FAILED", "CANCELLING"), "CANCELLING")
        withContext(Dispatchers.IO) { manager.cancelUniqueWork("upload_task_$taskId").result.get() }
        enqueueWorkManagerTask(taskId)
        Resource.Success(Unit)
    }

    suspend fun performCancellation(taskId: String): Resource<Unit> = api {
        val task = ownedTask(taskId)
        require(task.status == "CANCELLING")
        val response = httpClient.post("api/v1/videos/${task.videoId}/abort-upload") { setBody(AbortUploadRequestDto(task.uploadSessionId)) }
        val envelope = response.body<ApiEnvelope<AbortUploadResponseDto>>()
        if (response.status != HttpStatusCode.OK || !envelope.success) Resource.Error(envelope.error?.message ?: "Cancellation failed", response.status.value.toString())
        else { taskDao.transition(taskId, listOf("CANCELLING"), "CANCELLED"); cleanupDraft(task); Resource.Success(Unit) }
    }

    override suspend fun pauseUploadTask(taskId: String) {
        ownedTask(taskId)
        if (taskDao.transition(taskId, listOf("QUEUED", "UPLOADING", "RETRYING"), "PAUSED") == 1)
            withContext(Dispatchers.IO) { manager.cancelUniqueWork("upload_task_$taskId").result.get() }
    }
    override suspend fun resumeUploadTask(taskId: String) {
        ownedTask(taskId)
        withContext(Dispatchers.IO) { manager.cancelUniqueWork("upload_task_$taskId").result.get() }
        if (taskDao.transition(taskId, listOf("PAUSED"), "QUEUED") == 1) enqueueWorkManagerTask(taskId)
    }
    override suspend fun retryUploadTask(taskId: String) {
        val task = ownedTask(taskId)
        if(task.status=="DELETING") { deleteQueueVideo(taskId); return }
        if (task.status !in setOf("FAILED", "RETRYING", "CANCELLING")) return
        withContext(Dispatchers.IO) { manager.cancelUniqueWork("upload_task_$taskId").result.get() }
        if (task.status != "CANCELLING") taskDao.transition(taskId, listOf("FAILED", "RETRYING"), "QUEUED")
        enqueueWorkManagerTask(taskId)
    }
    override fun enqueueWorkManagerTask(taskId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("KEY_TASK_ID" to taskId))
            .addTag("vidoora_upload")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS).build()
        manager.enqueueUniqueWork("upload_task_$taskId", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun recoverForUser(userId: String) = withContext(Dispatchers.IO) {
        val expiry = System.currentTimeMillis() - 86_400_000L
        taskDao.getAllTasksOnce().forEach { task ->
            if (task.status in setOf("UPLOADED", "CANCELLED")) cleanupDraft(task)
            if (task.createdAt < expiry && task.status in setOf("PAUSED", "FAILED")) {
                taskDao.transition(task.id, listOf("PAUSED", "FAILED"), "FAILED", "Upload session expired; select the video again")
                cleanupDraft(task)
            }
        }
        taskDao.getRecoverableTasks(userId).forEach { enqueueWorkManagerTask(it.id) }
        val retained = taskDao.retainedDraftPaths().toSet()
        for (directory in listOf(File(context.filesDir, "upload_drafts"), File(context.cacheDir, "upload_drafts"))) {
            directory.listFiles()?.filter { it.absolutePath !in retained && it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach { it.delete() }
        }
    }
    suspend fun stopForLogout(userId: String) = withContext(Dispatchers.IO) {
        taskDao.getRecoverableTasks(userId).forEach { task ->
            if (task.status !in setOf("CANCELLING","DELETING")) taskDao.transition(task.id, listOf("QUEUED", "UPLOADING", "RETRYING", "COMPLETING"), "PAUSED")
            manager.cancelUniqueWork("upload_task_${task.id}").result.get()
        }
    }
    private suspend fun cleanupDraft(task: UploadTaskEntity) = withContext(Dispatchers.IO) {
        val file = File(task.sourceUri)
        val parent = file.parentFile?.canonicalFile ?: return@withContext
        if (parent in listOf(File(context.filesDir, "upload_drafts").canonicalFile, File(context.cacheDir, "upload_drafts").canonicalFile)) file.delete()
    }
    private suspend fun cleanupPreview(task: UploadTaskEntity) = withContext(Dispatchers.IO) {
        val directory=File(context.cacheDir,"upload_previews").canonicalFile
        val preview=File(directory,"${task.id}.jpg")
        if(preview.parentFile?.canonicalFile==directory) preview.delete()
    }
    private suspend fun <T> api(block: suspend () -> Resource<T>): Resource<T> = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Resource.Error(e.localizedMessage ?: "Upload request failed") }
}

@kotlinx.serialization.Serializable
private data class DeletionAcknowledgement(val accepted: Boolean)
