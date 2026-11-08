package com.example.vidoora.feature.playback

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.network.SharedVideoLink
import com.example.vidoora.core.network.VideoShareAccess
import com.example.vidoora.core.network.dto.ApiEnvelope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable data class VideoDetailsDto(val id: String,val title: String,val description: String?=null,val publicationState: String,
    val durationSeconds: Int,val visibility: String,val published: Boolean,val isOwner: Boolean,val canPlay: Boolean,val creatorName: String,val folderId: String?=null,val thumbnailVersion: String?=null,val processingPercent: Int?=null)
@Serializable data class EditVideoRequestDto(val title: String,val description: String?=null)
@Serializable data class PublicationRequestDto(val visibility: String)
@Serializable data class ShareLinkDto(val url: String)
data class VideoDetailsState(val loading: Boolean=true,val video: VideoDetailsDto?=null,val error: String?=null,val notice: String?=null,val editing: Boolean=false,val editTitle: String="",val editDescription: String="",val editError: String?=null,val deleted: Boolean=false,val moving: Boolean=false,val foldersLoaded: Boolean=false,val folderChoices: List<VideoFolderDto> = emptyList(),val selectedFolder: String?=null,val moveError: String?=null,val statusError: String?=null)

@HiltViewModel
class VideoDetailsViewModel @Inject constructor(private val client: HttpClient,private val shareAccess: VideoShareAccess,
    savedState: SavedStateHandle,private val uploads: com.example.vidoora.core.data.repository.UploadRepository) : ViewModel() {
    val thumbnails=PrivateThumbnailLoader(client,shareAccess::token)
    private val videoId=checkNotNull(savedState.get<String>("videoId"))
    private val mutable=MutableStateFlow(VideoDetailsState())
    val state=mutable.asStateFlow()
    private val links=Channel<String>(Channel.BUFFERED)
    val shareLinks=links.receiveAsFlow()
    private var revision=0
    private var observing=false
    private var observation=0
    private var loadedToken=shareAccess.token(videoId)
    private fun statusAllowed(): Boolean {
        val state=mutable.value
        return !state.loading && !state.editing && !state.moving && !state.deleted && state.video?.isOwner==true && VideoProcessingStatus.pending(state.video.publicationState)
    }
    private val statusPoller=ProcessingStatusPoller(viewModelScope,::statusAllowed,::updateProcessing)
    fun startObserving() { if(!observing) { observing=true; observation++; statusPoller.start() } }
    fun stopObserving() { observing=false; observation++; statusPoller.stop() }
    private suspend fun updateProcessing(): Boolean {
        val version=revision; val epoch=observation; val token=shareAccess.token(videoId)
        fun current()=observing && observation==epoch && revision==version && token==shareAccess.token(videoId) && statusAllowed()
        try {
            val response=client.get("api/v1/videos/$videoId/details") { token?.let { header("X-Vidoora-Share",it) } }
            if(response.status.value in setOf(401,403,404)) {
                if(current()) mutable.value=VideoDetailsState(loading=false,error="Video unavailable. Refresh or sign in again.")
                return false
            }
            check(response.status.value==200)
            val result=response.body<ApiEnvelope<VideoDetailsDto>>()
            val video=checkNotNull(result.data); check(result.success && video.id==videoId)
            if(current()) mutable.value=mutable.value.copy(video=video,statusError=null)
            return true
        } catch(e: CancellationException) { throw e }
        catch (_: Exception) {
            if(current()) mutable.value=mutable.value.copy(statusError="Status updates unavailable. Retrying automatically; you can also tap Refresh.")
            return false
        }
    }
    init { refresh() }
    fun refresh() { if(mutable.value.deleted) return; loadedToken=shareAccess.token(videoId); request(null) }
    fun ensureCurrentAccess() { if(loadedToken!=shareAccess.token(videoId)) refresh() }
    fun blockUploader() {
        revision++
        if(mutable.value.loading || mutable.value.video?.isOwner!=false) return
        viewModelScope.launch {
            mutable.value=mutable.value.copy(loading=true,error=null,notice=null)
            try {
                val response=client.post("api/v1/videos/$videoId/block-uploader") {
                    shareAccess.token(videoId)?.let { header("X-Vidoora-Share",it) }
                }
                if(response.status.value==200 && response.body<ApiEnvelope<SafetyResultDto>>().data?.accepted==true) {
                    mutable.value=VideoDetailsState(loading=false,notice="Uploader blocked. Their shared videos are unavailable to you. You can unblock them from Profile.")
                } else mutable.value=mutable.value.copy(loading=false,
                    video=if(response.status.value in setOf(401,403,404)) null else mutable.value.video,
                    error="Unable to block this uploader. Refresh and retry.")
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Cannot reach Vidoora. Please retry.") }
        }
    }
    fun deleteVideo() {
        revision++
        val state=mutable.value
        if(state.loading || state.deleted || state.video?.isOwner!=true) return
        mutable.value=state.copy(loading=true,error=null,notice=null)
        viewModelScope.launch {
            try {
                val queued=uploads.deleteQueuedVideoIfPresent(videoId)
                if(queued!=null) {
                    if(queued is com.example.vidoora.core.common.Resource.Success) {
                        shareAccess.forget(videoId)
                        mutable.value=VideoDetailsState(loading=false,deleted=true,notice="Video deleted. Storage cleanup is queued.")
                    } else mutable.value=mutable.value.copy(loading=false,error=(queued as? com.example.vidoora.core.common.Resource.Error)?.message ?: "Deletion pending. Retry from Upload queue.")
                    return@launch
                }
                val response=client.delete("api/v1/videos/$videoId")
                if(response.status.value==200) {
                    val result=response.body<ApiEnvelope<SafetyResultDto>>()
                    check(result.success && result.data?.accepted==true)
                    uploads.removeDeletedVideo(videoId)
                    shareAccess.forget(videoId)
                    mutable.value=VideoDetailsState(loading=false,deleted=true,notice="Video deleted. Shared access has stopped. Storage cleanup is queued.")
                } else if(response.status.value in setOf(401,403,404)) {
                    mutable.value=VideoDetailsState(loading=false,error="Video unavailable or deletion is not allowed.")
                } else mutable.value=mutable.value.copy(loading=false,error=if(response.status.value==409)
                    "Cancel the active upload in Upload Queue before deleting this video." else "Unable to delete. Please retry.")
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Could not confirm deletion. Check your connection, refresh, or retry.") }
        }
    }
    fun changeThumbnail(context: android.content.Context,uri: android.net.Uri) {
        val state=mutable.value
        if(state.loading || state.video?.isOwner!=true || state.video.publicationState!="PROCESSED") return
        revision++
        mutable.value=state.copy(loading=true,error=null,notice=null)
        viewModelScope.launch {
            try {
                val bytes=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ThumbnailPreparation.fromUri(context.applicationContext,uri) }
                val response=client.put("api/v1/videos/$videoId/thumbnail") {
                    contentType(io.ktor.http.ContentType.Image.JPEG)
                    setBody(io.ktor.http.content.ByteArrayContent(bytes,io.ktor.http.ContentType.Image.JPEG))
                }
                check(response.status.value==200)
                val result=response.body<ApiEnvelope<VideoDetailsDto>>()
                check(result.success && result.data?.id==videoId && result.data.isOwner)
                mutable.value=VideoDetailsState(loading=false,video=result.data,notice="Thumbnail updated")
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Could not change thumbnail. Choose an image up to 8 MB and retry.") }
        }
    }
    fun startMoving() {
        revision++
        val state=mutable.value
        if(state.loading || state.video?.isOwner!=true) return
        mutable.value=state.copy(moving=true,loading=true,foldersLoaded=false,moveError=null,selectedFolder=state.video.folderId)
        viewModelScope.launch {
            try {
                val response=client.get("api/v1/folders"); check(response.status.value==200)
                val result=response.body<ApiEnvelope<List<VideoFolderDto>>>(); check(result.success)
                mutable.value=mutable.value.copy(loading=false,foldersLoaded=true,folderChoices=checkNotNull(result.data))
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,moveError="Cannot load folders. Retry or cancel.") }
        }
    }
    fun selectFolder(id: String?) {
        if(!mutable.value.loading && (id==null || mutable.value.folderChoices.any { it.id==id })) mutable.value=mutable.value.copy(selectedFolder=id,moveError=null)
    }
    fun cancelMoving() { if(!mutable.value.loading) mutable.value=mutable.value.copy(moving=false,moveError=null) }
    fun saveFolderMove() {
        revision++
        val state=mutable.value
        if(state.loading || !state.moving || !state.foldersLoaded || state.video?.isOwner!=true) return
        mutable.value=state.copy(loading=true,moveError=null)
        viewModelScope.launch {
            try {
                val response=client.put("api/v1/videos/$videoId/folder") { contentType(io.ktor.http.ContentType.Application.Json); setBody(MoveFolderDto(state.selectedFolder)) }
                if(response.status.value in setOf(401,403,404)) { mutable.value=VideoDetailsState(loading=false,error="Video or folder unavailable. Refresh and retry."); return@launch }
                check(response.status.value==200)
                val result=response.body<ApiEnvelope<VideoDetailsDto>>(); check(result.success && result.data?.isOwner==true)
                mutable.value=VideoDetailsState(loading=false,video=result.data,notice="Video folder updated. Sharing is unchanged.")
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,moveError="Cannot move video. Your selection is still here; retry or cancel.") }
        }
    }
    fun startEditing() {
        revision++
        val state=mutable.value; val video=state.video ?: return
        if(state.loading || !video.isOwner) return
        mutable.value=state.copy(editing=true,editTitle=video.title,editDescription=video.description ?: "",editError=null)
    }
    fun cancelEditing() { if(!mutable.value.loading) mutable.value=mutable.value.copy(editing=false,editError=null) }
    fun setEditTitle(value: String) { mutable.value=mutable.value.copy(editTitle=value,editError=null) }
    fun setEditDescription(value: String) { mutable.value=mutable.value.copy(editDescription=value,editError=null) }
    fun saveDetails() {
        revision++
        val state=mutable.value
        if(state.loading || !state.editing || state.video?.isOwner!=true) return
        val title=state.editTitle.trim()
        if(title.isEmpty() || title.length>128 || state.editDescription.length>5000) {
            mutable.value=state.copy(editError="Enter a title of 1–128 characters and a description of at most 5000 characters.")
            return
        }
        mutable.value=state.copy(loading=true,editError=null)
        viewModelScope.launch {
            try {
                val response=client.patch("api/v1/videos/$videoId/details") {
                    contentType(io.ktor.http.ContentType.Application.Json)
                    setBody(EditVideoRequestDto(title,state.editDescription.trim().takeIf { it.isNotEmpty() }))
                }
                if(response.status.value==200) {
                    val result=response.body<ApiEnvelope<VideoDetailsDto>>()
                    check(result.success && result.data?.isOwner==true)
                    mutable.value=VideoDetailsState(loading=false,video=result.data,notice="Video details updated.")
                } else if(response.status.value in setOf(401,403,404)) {
                    mutable.value=VideoDetailsState(loading=false,error="Video unavailable or editing is not allowed.")
                } else mutable.value=mutable.value.copy(loading=false,editError="Unable to save. Your changes are still here; please retry.")
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,editError="Cannot reach Vidoora. Your changes are still here; please retry.") }
        }
    }
    /** Refresh access on an explicit Watch tap before any ad or navigation. */
    fun watch(onReady: (VideoDetailsDto)->Unit) {
        val state=mutable.value
        if(state.loading || state.deleted || state.video?.canPlay!=true) return
        val version=++revision; val token=shareAccess.token(videoId)
        mutable.value=state.copy(loading=true,error=null,statusError=null)
        viewModelScope.launch {
            try {
                val response=client.get("api/v1/videos/$videoId/details") { token?.let { header("X-Vidoora-Share",it) } }
                if(revision!=version || token!=shareAccess.token(videoId)) return@launch
                if(response.status.value==200) {
                    val result=response.body<ApiEnvelope<VideoDetailsDto>>()
                    val video=result.data
                    if(result.success && video?.id==videoId && video.canPlay) {
                        mutable.value=mutable.value.copy(loading=false,video=video)
                        onReady(video)
                    } else mutable.value=VideoDetailsState(loading=false,error="Video unavailable or not ready to watch.")
                } else if(response.status.value in setOf(401,403,404)) {
                    mutable.value=VideoDetailsState(loading=false,error="Video unavailable.")
                } else mutable.value=mutable.value.copy(loading=false,error="Cannot check video access. Please retry.")
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { if(revision==version) mutable.value=mutable.value.copy(loading=false,error="Cannot check video access. Please retry.") }
        }
    }
    fun enableLinkSharing() { request("publish","UNLISTED") }
    fun unpublish() { request("unpublish") }
    private fun request(action: String?,visibility: String?=null) {
        revision++
        viewModelScope.launch {
            mutable.value=mutable.value.copy(loading=true,error=null,statusError=null)
            try {
                val response=if(action==null) client.get("api/v1/videos/$videoId/details") {
                    shareAccess.token(videoId)?.let { header("X-Vidoora-Share",it) }
                } else client.post("api/v1/videos/$videoId/$action") { if(visibility!=null) setBody(PublicationRequestDto(visibility)) }
                if(response.status.value==200) {
                    val result=response.body<ApiEnvelope<VideoDetailsDto>>()
                    mutable.value=VideoDetailsState(false,result.data,if(result.success) null else "Video unavailable.")
                } else mutable.value=mutable.value.copy(loading=false,video=if(response.status.value in setOf(401,403,404)) null else mutable.value.video,error=if(response.status.value==409) "This video is not ready for that action. Check its processing status." else "Video unavailable or this action is not allowed.")
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Cannot reach Vidoora. Check your connection and retry.") }
        }
    }
    fun share() {
        revision++
        if(mutable.value.loading) return
        viewModelScope.launch {
            mutable.value=mutable.value.copy(loading=true,error=null,statusError=null)
            try {
                val response=client.post("api/v1/videos/$videoId/share-link")
                if(response.status.value==200) {
                    val result=response.body<ApiEnvelope<ShareLinkDto>>()
                    val link=result.data?.url
                    if(result.success && link!=null && SharedVideoLink.parse(link)!=null) links.send(link)
                    else throw IllegalStateException()
                    mutable.value=mutable.value.copy(loading=false)
                } else mutable.value=mutable.value.copy(loading=false,error="Enable link sharing before creating a link.")
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Unable to create a share link. Please retry.") }
        }
    }
}

@HiltViewModel
class VideoNavigationViewModel @Inject constructor(val shareAccess: VideoShareAccess,
    val analytics: com.example.vidoora.feature.analytics.PlaybackAnalytics,
    val analyticsStorage: com.example.vidoora.core.security.SecureStorageManager) : ViewModel()















