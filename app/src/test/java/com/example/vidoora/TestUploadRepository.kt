package com.example.vidoora

import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.data.repository.UploadRepository
import com.example.vidoora.core.database.entity.UploadTaskEntity
import com.example.vidoora.core.network.dto.CompleteUploadResponseDto
import kotlinx.coroutines.flow.flowOf

/** Non-upload tests explicitly supply an empty queue, rather than bypassing production injection. */
class TestUploadRepository : UploadRepository {
    val removed=mutableListOf<String>()
    var queuedResult: Resource<Unit>?=null
    override suspend fun deleteQueuedVideoIfPresent(videoId: String)=queuedResult
    override suspend fun removeDeletedVideo(videoId: String) { removed+=videoId }
    override suspend fun deleteQueueVideo(taskId: String): Resource<Unit> = error("Unexpected queue action")
    override fun getAllTasksFlow()=flowOf(emptyList<UploadTaskEntity>())
    override suspend fun getTaskById(taskId: String): UploadTaskEntity?=null
    override suspend fun initiateAndQueueUpload(sourceUri: String,title: String,description: String?,visibility: String,fileSizeBytes: Long,mimeType: String): Resource<String> = error("Unexpected upload")
    override suspend fun refreshPartUrls(taskId: String,videoId: String,uploadSessionId: String,partNumbers: List<Int>): Resource<Unit> = error("Unexpected upload")
    override suspend fun completeUploadTask(taskId: String,videoId: String,uploadSessionId: String): Resource<CompleteUploadResponseDto> = error("Unexpected upload")
    override suspend fun abortUploadTask(taskId: String,videoId: String,uploadSessionId: String): Resource<Unit> = error("Unexpected upload")
    override suspend fun pauseUploadTask(taskId: String) { error("Unexpected upload") }
    override suspend fun resumeUploadTask(taskId: String) { error("Unexpected upload") }
    override suspend fun retryUploadTask(taskId: String) { error("Unexpected upload") }
    override fun enqueueWorkManagerTask(taskId: String) { error("Unexpected upload") }
}
