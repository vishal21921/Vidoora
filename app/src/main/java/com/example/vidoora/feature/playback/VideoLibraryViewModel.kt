package com.example.vidoora.feature.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.network.dto.ApiEnvelope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable data class LibraryVideoDto(val id: String,val title: String,val publicationState: String,
    val durationSeconds: Int,val visibility: String,val published: Boolean=false,val thumbnailAvailable: Boolean=false,val folderId: String?=null,val thumbnailVersion: String?=null)
@Serializable data class OwnerVideoStatusRequestDto(val ids: List<String>,val search: String="",val sort: String="NEWEST",val state: String="ALL",val sharing: String="ALL",val folder: String="ALL")
@Serializable data class LibraryPageDto(val items: List<LibraryVideoDto>,val nextCursor: String?=null)
data class VideoLibraryState(val loading: Boolean=true,val videos: List<LibraryVideoDto> = emptyList(),val error: String?=null,
    val searchText: String="",val appliedSearch: String="",val nextCursor: String?=null,val sortOrder: String="NEWEST",val processingFilter: String="ALL",val sharingFilter: String="ALL",val folderFilter: String="ALL",val statusError: String?=null)

@HiltViewModel
class VideoLibraryViewModel @Inject constructor(private val client: HttpClient) : ViewModel() {
    val thumbnails=PrivateThumbnailLoader(client)
    private val mutableState=MutableStateFlow(VideoLibraryState())
    val state=mutableState.asStateFlow()
    private var requestJob: Job?=null
    private var resumeJob: Job?=null
    private val statusMutex=kotlinx.coroutines.sync.Mutex()
    private var generation=0
    private var observing=false
    private var observation=0
    private var visibleIds: Set<String> = emptySet()
    private val statusPoller=ProcessingStatusPoller(viewModelScope,
        { !mutableState.value.loading && pendingVisible().isNotEmpty() },::updateProcessing)
    fun setVisibleVideos(ids: List<String>) { visibleIds=ids.take(50).toSet() }
    fun startObserving() { if(!observing) { observing=true; observation++; statusPoller.start() } }
    fun stopObserving() { observing=false; observation++; statusPoller.stop(); resumeJob?.cancel() }
    private fun pendingVisible()=mutableState.value.videos.filter { it.id in visibleIds && VideoProcessingStatus.pending(it.publicationState) }.take(50).map { it.id }
    private suspend fun updateProcessing(): Boolean {
        return updateStatuses(pendingVisible())
    }
    private suspend fun updateStatuses(ids: List<String>): Boolean {
        statusMutex.lock()
        try { return updateStatusesLocked(ids) } finally { statusMutex.unlock() }
    }
    private suspend fun updateStatusesLocked(ids: List<String>): Boolean {
        if(!observing) return false
        if(ids.isEmpty()) return true
        val before=mutableState.value; val version=generation; val epoch=observation
        fun current()=observing && epoch==observation && version==generation && !mutableState.value.loading
        try {
            val response=client.post("api/v1/videos/mine/status") {
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(OwnerVideoStatusRequestDto(ids,before.appliedSearch,before.sortOrder,before.processingFilter,before.sharingFilter,before.folderFilter))
            }
            if(response.status.value in setOf(401,403)) {
                if(current()) mutableState.value=mutableState.value.copy(videos=emptyList(),nextCursor=null,error="Sign in again to view your videos.",statusError=null)
                return false
            }
            check(response.status.value==200)
            val result=response.body<ApiEnvelope<List<LibraryVideoDto>>>(); check(result.success)
            val items=checkNotNull(result.data); check(items.all { it.id in ids } && items.distinctBy { it.id }.size==items.size)
            if(current()) {
                val replacements=items.associateBy { it.id }
                mutableState.value=mutableState.value.copy(videos=mutableState.value.videos.mapNotNull { if(it.id in ids) replacements[it.id] else it },statusError=null)
            }
            return true
        } catch(e: CancellationException) { throw e }
        catch (_: Exception) {
            if(current()) mutableState.value=mutableState.value.copy(statusError="Status updates unavailable. Retrying automatically; you can also tap Refresh.")
            return false
        }
    }
    init { refresh() }
    fun setSearchText(value: String) { mutableState.value=mutableState.value.copy(searchText=value.take(100)) }
    fun search() { load(reset=true,query=mutableState.value.searchText.trim()) }
    fun onResume() {
        val loaded = mutableState.value
        if (loaded.loading) return
        if (loaded.videos.isEmpty()) refresh()
        else {
            resumeJob?.cancel()
            val version=generation
            resumeJob=viewModelScope.launch {
            // Reconcile loaded pages without replacing their cursor, filters or scroll anchors.
            for (ids in loaded.videos.map { it.id }.chunked(50)) {
                if(!observing || generation!=version) break
                if (!updateStatuses(ids)) break
            }
            }
        }
    }
    fun refresh() { load(reset=true,query=mutableState.value.appliedSearch) }
    fun setSort(value: String) {
        if(value !in setOf("NEWEST","OLDEST") || value==mutableState.value.sortOrder) return
        mutableState.value=mutableState.value.copy(sortOrder=value)
        refresh()
    }
    fun setProcessingFilter(value: String) {
        if(value !in setOf("ALL","READY","PROCESSING","FAILED") || value==mutableState.value.processingFilter) return
        mutableState.value=mutableState.value.copy(processingFilter=value)
        refresh()
    }
    fun setSharingFilter(value: String) {
        if(value !in setOf("ALL","PRIVATE","SHARED") || value==mutableState.value.sharingFilter) return
        mutableState.value=mutableState.value.copy(sharingFilter=value)
        refresh()
    }
    fun setFolder(value: String) {
        if(value==mutableState.value.folderFilter) return
        mutableState.value=mutableState.value.copy(folderFilter=value)
        refresh()
    }
    fun resetView() {
        mutableState.value=mutableState.value.copy(sortOrder="NEWEST",processingFilter="ALL",sharingFilter="ALL",folderFilter="ALL",searchText="")
        load(reset=true,query="")
    }
    fun loadMore() { load(reset=false,query=mutableState.value.appliedSearch) }
    private fun load(reset: Boolean,query: String) {
        val before=mutableState.value
        if(!reset && (before.loading || before.nextCursor==null)) return
        requestJob?.cancel()
        val version=++generation
        val cursor=if(reset) null else before.nextCursor
        mutableState.value=before.copy(loading=true,error=null,statusError=null,appliedSearch=query,
            videos=if(reset) emptyList() else before.videos,nextCursor=cursor)
        requestJob=viewModelScope.launch {
            try {
                val response=client.get("api/v1/videos/mine/page") {
                    if(query.isNotEmpty()) parameter("q",query)
                    cursor?.let { parameter("cursor",it) }
                    parameter("sort",before.sortOrder); parameter("state",before.processingFilter); parameter("sharing",before.sharingFilter); parameter("folder",before.folderFilter)
                }
                if(response.status.value!=200) {
                    if(version==generation) mutableState.value=mutableState.value.copy(loading=false,
                        videos=if(response.status.value in setOf(401,403,404)) emptyList() else mutableState.value.videos,
                        nextCursor=if(response.status.value in setOf(401,403,404)) null else cursor,
                        error="Unable to load your videos. Check your connection and sign-in.")
                } else {
                    val result=response.body<ApiEnvelope<LibraryPageDto>>()
                    check(result.success)
                    val page=checkNotNull(result.data)
                    if(version==generation) mutableState.value=mutableState.value.copy(loading=false,
                        videos=(mutableState.value.videos+page.items).distinctBy { it.id },nextCursor=page.nextCursor,error=null)
                }
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) {
                if(version==generation) mutableState.value=mutableState.value.copy(loading=false,error="Cannot reach Vidoora. Retry Search or Load more.")
            }
        }
    }
}





