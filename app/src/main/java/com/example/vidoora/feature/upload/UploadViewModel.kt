package com.example.vidoora.feature.upload

import io.ktor.client.request.*
import io.ktor.client.call.body
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.data.repository.UploadRepository
import com.example.vidoora.core.database.entity.UploadTaskEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

data class SelectedVideoInfo(
    val uriString: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String,
    val isValidSize: Boolean
)

sealed class UploadFormUiState {
    object Idle : UploadFormUiState()
    object Submitting : UploadFormUiState()
    data class Success(val taskId: String) : UploadFormUiState()
    data class Error(val message: String) : UploadFormUiState()
}

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val client: io.ktor.client.HttpClient
) : ViewModel() {
    var processing by mutableStateOf<Map<String,QueueProcessing>>(emptyMap());private set
    fun clearProcessing() {processing=emptyMap()}
    suspend fun refreshProcessing() {
        val ids=uploadTasks.value.filter {it.status=="UPLOADED" && processing[it.videoId]?.publicationState !in setOf("PROCESSED","PROCESSING_FAILED")}.take(50).map {it.videoId}
        if(ids.isEmpty())return
        try {
            val response=client.post("api/v1/videos/mine/status") {setBody(mapOf("ids" to ids))}
            check(response.status.value==200)
            val result=response.body<com.example.vidoora.core.network.dto.ApiEnvelope<List<QueueProcessing>>>()
            processing=processing+checkNotNull(result.data).associateBy {it.id}
        } catch(e: CancellationException){throw e}catch(_: Exception){ /* Never fabricate progress when status cannot be read. */ }
    }
    var thumbnails by mutableStateOf(com.example.vidoora.feature.playback.PrivateThumbnailLoader(client))
        private set
    fun refreshPreviews() { thumbnails=com.example.vidoora.feature.playback.PrivateThumbnailLoader(client) }

    private val _selectedVideo = MutableStateFlow<SelectedVideoInfo?>(null)
    val selectedVideo: StateFlow<SelectedVideoInfo?> = _selectedVideo.asStateFlow()

    private val _formState = MutableStateFlow<UploadFormUiState>(UploadFormUiState.Idle)
    val formState: StateFlow<UploadFormUiState> = _formState.asStateFlow()
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    val uploadTasks: StateFlow<List<UploadTaskEntity>> = uploadRepository.getAllTasksFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onVideoSelected(uriString: String, fileName: String, fileSizeBytes: Long, mimeType: String) {
        val max1GiB = 1073741824L
        val isValid = fileSizeBytes > 0 && fileSizeBytes <= max1GiB
        _selectedVideo.value = SelectedVideoInfo(
            uriString = uriString,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            mimeType = mimeType.ifBlank { "video/mp4" },
            isValidSize = isValid
        )
        _formState.value = UploadFormUiState.Idle
    }

    fun startUpload(title: String, description: String?, visibility: String) {
        val video = _selectedVideo.value
        if (video == null) {
            _formState.value = UploadFormUiState.Error("Please select a video file first.")
            return
        }
        if (!video.isValidSize) {
            _formState.value = UploadFormUiState.Error("File size exceeds maximum allowed 1 GB limit.")
            return
        }
        if (title.isBlank()) {
            _formState.value = UploadFormUiState.Error("Title cannot be empty.")
            return
        }

        viewModelScope.launch {
            _formState.value = UploadFormUiState.Submitting
            val result = uploadRepository.initiateAndQueueUpload(
                sourceUri = video.uriString,
                title = title.trim(),
                description = description?.trim(),
                visibility = visibility,
                fileSizeBytes = video.fileSizeBytes,
                mimeType = video.mimeType
            )

            when (result) {
                is Resource.Success -> {
                    _formState.value = UploadFormUiState.Success(result.data)
                    _selectedVideo.value = null
                }
                is Resource.Error -> {
                    _formState.value = UploadFormUiState.Error(result.message)
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun pauseUpload(taskId: String) {
        performAction {
            uploadRepository.pauseUploadTask(taskId)
        }
    }

    fun deleteUpload(taskId: String) { performAction {
        val result=uploadRepository.deleteQueueVideo(taskId)
        if(result is Resource.Error) _actionError.value=result.message
    } }

    fun resumeUpload(taskId: String) {
        performAction {
            uploadRepository.resumeUploadTask(taskId)
        }
    }

    fun retryUpload(taskId: String) {
        performAction {
            uploadRepository.retryUploadTask(taskId)
        }
    }

    fun cancelUpload(taskId: String, videoId: String, uploadSessionId: String) {
        performAction {
            val result = uploadRepository.abortUploadTask(taskId, videoId, uploadSessionId)
            if (result is Resource.Error) _actionError.value = result.message
        }
    }

    private fun performAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            _actionError.value = null
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _actionError.value = e.localizedMessage ?: "Unable to update upload" }
        }
    }

    fun clearFormState() {
        _formState.value = UploadFormUiState.Idle
    }
}

@kotlinx.serialization.Serializable data class QueueProcessing(val id: String,val publicationState: String,val processingPercent: Int?=null)
