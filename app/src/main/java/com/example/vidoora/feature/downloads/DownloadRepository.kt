package com.example.vidoora.feature.downloads

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.core.security.SecureStorageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal class DownloadHttpFailure(val status: Int): IOException("Download request unavailable")
@Singleton
class DownloadRepository @Inject constructor(val store: OfflineStore,private val client: HttpClient,@ApplicationContext private val context: Context) {
    suspend fun quota(): DownloadQuotaDto {
        val response=client.get("api/v1/downloads/quota")
        check(response.status==HttpStatusCode.OK)
        return checkNotNull(response.body<ApiEnvelope<DownloadQuotaDto>>().data)
    }
    private fun pending(item: OfflineItem)=item.manifest?.let{(item.cancellations+CancelTransfer(it.id,item.clientId)).distinct()} ?: item.cancellations
    private fun work(item: OfflineItem) {
        val request=OneTimeWorkRequestBuilder<PrivateDownloadWorker>().setInputData(workDataOf("account" to item.account,"id" to item.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("private-download-${item.id}",ExistingWorkPolicy.REPLACE,request)
    }
    suspend fun start(videoId: String,token: String?): String=withContext(Dispatchers.IO) {
        val item=store.create(videoId,token)
        if(item.state!="COMPLETE" || !store.valid(item)) {
            val updated=store.update(item.account,item.id){it.copy(state="QUEUED",message=null,clientId=java.util.UUID.randomUUID().toString(),cancellations=pending(it),cancelPending=it.manifest!=null || it.cancellations.isNotEmpty())}!!;work(updated)
        }
        item.id
    }
    suspend fun pause(item: OfflineItem)=withContext(Dispatchers.IO) {
        require(store.account()==item.account)
        val paused=store.update(item.account,item.id){it.copy(state="PAUSED",cancellations=pending(it),cancelPending=true,validMs=0)} ?: return@withContext
        work(paused)
    }
    suspend fun remove(item: OfflineItem)=withContext(Dispatchers.IO) {
        require(store.account()==item.account)
        val removing=store.update(item.account,item.id){it.copy(state="REMOVING",cancellations=pending(it),cancelPending=true,validMs=0)} ?: return@withContext
        store.eraseChunks(item.account,item.id);work(removing)
    }
    suspend fun resume(item: OfflineItem)=withContext(Dispatchers.IO) {
        require(store.account()==item.account)
        val updated=store.update(item.account,item.id){it.copy(state="QUEUED",message=null,clientId=java.util.UUID.randomUUID().toString(),cancellations=pending(it),cancelPending=it.manifest!=null || it.cancellations.isNotEmpty())} ?: return@withContext
        work(updated)
    }
    private fun current(item: OfflineItem)=store.account()==item.account
    private fun HttpRequestBuilder.capability(item: OfflineItem){header("X-Vidoora-Download",item.clientId);item.shareToken?.let{header("X-Vidoora-Share",it)}}
    suspend fun validate(item: OfflineItem): Boolean=withContext(Dispatchers.IO) {
        if(!current(item)) return@withContext false
        val manifest=item.manifest ?: return@withContext false
        if(item.hashes.keys!=manifest.paths.toSet()) return@withContext false
        val start=SystemClock.elapsedRealtime();val wall=System.currentTimeMillis()
        try {
            val response=client.post("api/v1/downloads/${manifest.id}/complete"){capability(item);setBody(Proof(item.hashes))}
            if(!current(item)) return@withContext false
            if(response.status.value !in 200..299) {
                if(response.status.value in 400..499) store.update(item.account,item.id){it.copy(validMs=0,message="Video or plan access is unavailable. Open the current video link online.")}
                return@withContext response.status.value>=500 && store.get(item.account,item.id)?.let(store::valid)==true
            }
            val lease=response.body<ApiEnvelope<OfflineLease>>().takeIf{it.success}?.data ?: return@withContext false
            store.anchor(item.account,item.id,lease,start,wall,item.clientId,manifest.id)
        }catch(e: CancellationException){throw e}catch(_: Exception){store.get(item.account,item.id)?.let(store::valid)==true}
    }
    internal suspend fun transfer(account: String,id: String,progress: suspend (Int)->Unit): Boolean=withContext(Dispatchers.IO) {
        var item=store.get(account,id) ?: return@withContext true
        if(!current(item)) return@withContext true
        if(item.cancelPending) {
            val cancellations=item.cancellations.ifEmpty{pending(item)}
            for(cancel in cancellations) {
                val response=client.delete("api/v1/downloads/${cancel.reservationId}"){header("X-Vidoora-Download",cancel.clientId)}
                if(response.status.value !in 200..299 && response.status.value !in listOf(403,404)) throw IOException("Cancellation pending")
            }
            if(!current(item)) return@withContext true
            item=store.update(account,id){latest->val remaining=latest.cancellations-cancellations.toSet();latest.copy(cancellations=remaining,cancelPending=remaining.isNotEmpty())} ?: return@withContext true
            if(item.cancelPending) return@withContext false
            if(item.state=="REMOVING"){store.erase(account,id);return@withContext true}
        }
        if(item.state !in listOf("QUEUED","DOWNLOADING")) return@withContext true
        val response=client.post("api/v1/downloads/video/${item.videoId}"){capability(item)}
        if(response.status.value !in 200..299) throw DownloadHttpFailure(response.status.value)
        val manifest=response.body<ApiEnvelope<OfflineManifest>>().takeIf{it.success}?.data ?: throw IOException("Invalid download response")
        require(manifest.videoId==item.videoId && manifest.paths.size in 2..10001 && manifest.paths.distinct().size==manifest.paths.size &&
            manifest.paths.all{Regex("[0-9]{1,3}p/(?:index\\.m3u8|segment_[0-9]{6}\\.ts)").matches(it)} && manifest.entry in manifest.paths)
        val updated=store.update(account,id){old ->
            if(old.state !in listOf("QUEUED","DOWNLOADING")) old
            else if(old.manifest?.version!=manifest.version || old.manifest?.id!=manifest.id) old.copy(manifest=manifest,state="DOWNLOADING",hashes=emptyMap(),sizes=emptyMap(),bytes=0,validMs=0)
            else old.copy(manifest=manifest,state="DOWNLOADING",validMs=0)
        } ?: return@withContext true
        item=updated
        for(path in manifest.paths) {
            ensureActive();val latest=store.get(account,id) ?: return@withContext true
            if(!current(latest) || latest.state!="DOWNLOADING" || latest.clientId!=item.clientId || latest.manifest?.version!=manifest.version) return@withContext true
            if(path !in latest.hashes || !store.hasChunk(latest,path)) {
                val bytes=client.prepareGet("api/v1/downloads/${manifest.id}/asset"){capability(latest);parameter("path",path)}.execute {reply ->
                    if(reply.status.value!=200) throw DownloadHttpFailure(reply.status.value)
                    val output=ByteArrayOutputStream();val channel=reply.bodyAsChannel();val buffer=ByteArray(64*1024)
                    while(true){ensureActive();val n=channel.readAvailable(buffer,0,buffer.size);if(n<0) break
                        if(output.size()+n>16*1024*1024) throw IOException("Download asset too large");output.write(buffer,0,n)}
                    output.toByteArray()
                }
                if(!store.chunk(account,id,path,bytes,manifest.version,item.clientId)) return@withContext true
            }
            progress((store.get(account,id)!!.hashes.size*100/manifest.paths.size).coerceIn(0,100))
        }
        val done=store.get(account,id) ?: return@withContext true
        if(!validate(done)) throw IOException("Download needs validation")
        true
    }
    internal suspend fun failed(account: String,id: String,terminal: Boolean) {
        withContext(Dispatchers.IO){store.update(account,id){if(it.state in listOf("QUEUED","DOWNLOADING")) it.copy(
            state=if(terminal) "FAILED" else "QUEUED",cancellations=if(terminal) pending(it) else it.cancellations,cancelPending=terminal || it.cancelPending,
            message=if(terminal) "Download unavailable. Check your plan, video link and storage, then retry." else "Waiting for connection. Will retry.") else it}}
    }
    fun recover() {
        CoroutineScope(Dispatchers.IO).launch {store.list(true).filter{it.state in listOf("QUEUED","DOWNLOADING") || it.cancelPending}.forEach(::work)}
    }
}

@HiltWorker
class PrivateDownloadWorker @AssistedInject constructor(@Assisted private val context: Context,@Assisted parameters: WorkerParameters,
    private val repository: DownloadRepository): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        val account=inputData.getString("account") ?: return Result.failure();val id=inputData.getString("id") ?: return Result.failure()
        if(repository.store.account()!=account) return Result.success()
        return try {
            val row=repository.store.get(account,id)
            if(row?.state in listOf("QUEUED","DOWNLOADING")) setForeground(notification(0))
            if(repository.transfer(account,id){setForeground(notification(it))}) Result.success() else Result.retry()
        }catch(e: CancellationException){throw e}
        catch(e: Exception){
            val terminal=(e is DownloadHttpFailure && e.status in listOf(400,403,404,409)) || e is IllegalArgumentException || runAttemptCount>=8
            repository.failed(account,id,terminal)
            // Terminal failures get another worker attempt solely to release the unused server reservation.
            if(terminal && repository.store.get(account,id)?.cancelPending!=true) Result.failure() else Result.retry()
        }
    }
    private fun notification(percent: Int): ForegroundInfo {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("private_downloads","Private downloads",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(context,"private_downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Vidoora download").setContentText("$percent% - encrypted in-app storage").setProgress(100,percent,false).setOngoing(true).build()
        val notificationId=0x40000000 or (id.hashCode() and 0x0fffffff)
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(notificationId,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(notificationId,notification)
    }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(val repository: DownloadRepository,storage: SecureStorageManager): ViewModel() {
    val items=combine(repository.store.changes,storage.sessionChanges){_,_->Unit}.mapLatest{withContext(Dispatchers.IO){repository.store.list()}}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val quota=MutableStateFlow<DownloadQuotaDto?>(null)
    suspend fun refreshQuota() {try {quota.value=repository.quota()}catch(e: CancellationException){throw e}catch(_: Exception){quota.value=null}}
    val error=MutableStateFlow<String?>(null)
    fun start(video: String,token: String?,done: ()->Unit){viewModelScope.launch{try{repository.start(video,token);done()}catch(e: CancellationException){throw e}catch(_: Exception){error.value="Unable to start download. Check sign-in and storage."}}}
    fun resume(item: OfflineItem){viewModelScope.launch{repository.resume(item)}}
    fun pause(item: OfflineItem){viewModelScope.launch{repository.pause(item)}}
    fun remove(item: OfflineItem){viewModelScope.launch{repository.remove(item)}}
}
