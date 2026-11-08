package com.example.vidoora.feature.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.network.dto.ApiEnvelope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable data class VideoFolderDto(val id: String,val name: String)
@Serializable data class FolderNameDto(val name: String)
@Serializable data class MoveFolderDto(val folderId: String?=null)
data class FoldersState(val loading: Boolean=true,val loaded: Boolean=false,val items: List<VideoFolderDto> = emptyList(),val error: String?=null,val change: Int=0)

@HiltViewModel
class FoldersViewModel @Inject constructor(private val client: HttpClient) : ViewModel() {
    private val mutable=MutableStateFlow(FoldersState())
    val state=mutable.asStateFlow()
    init { refresh() }
    fun refresh() {
        viewModelScope.launch {
            mutable.value=mutable.value.copy(loading=true,error=null)
            try {
                val response=client.get("api/v1/folders"); check(response.status.value==200)
                val data=response.body<ApiEnvelope<List<VideoFolderDto>>>(); check(data.success)
                mutable.value=mutable.value.copy(loading=false,loaded=true,items=checkNotNull(data.data))
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Cannot load folders. Please retry.") }
        }
    }
    fun save(name: String,id: String?) {
        if(mutable.value.loading || !mutable.value.loaded) return
        if(name.trim().length !in 1..64) { mutable.value=mutable.value.copy(error="Use a folder name of 1–64 characters."); return }
        mutable.value=mutable.value.copy(loading=true,error=null)
        viewModelScope.launch {
            try {
                val response=if(id==null) client.post("api/v1/folders") { contentType(ContentType.Application.Json); setBody(FolderNameDto(name.trim())) }
                    else client.patch("api/v1/folders/$id") { contentType(ContentType.Application.Json); setBody(FolderNameDto(name.trim())) }
                check(response.status.value==200)
                val result=response.body<ApiEnvelope<VideoFolderDto>>(); check(result.success)
                val folder=checkNotNull(result.data)
                mutable.value=mutable.value.copy(loading=false,loaded=true,items=(mutable.value.items.filterNot { it.id==folder.id }+folder).sortedBy { it.name.lowercase() },change=mutable.value.change+1)
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Cannot save folder. Use a unique name (maximum 100 folders). Refresh or retry.") }
        }
    }
    fun remove(id: String) {
        if(mutable.value.loading || !mutable.value.loaded) return
        mutable.value=mutable.value.copy(loading=true,error=null)
        viewModelScope.launch {
            try {
                val response=client.delete("api/v1/folders/$id"); check(response.status.value==200)
                val result=response.body<ApiEnvelope<SafetyResultDto>>(); check(result.success && result.data?.accepted==true)
                mutable.value=mutable.value.copy(loading=false,items=mutable.value.items.filterNot { it.id==id },change=mutable.value.change+1)
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Could not confirm folder removal. Refresh or retry. Videos are kept.") }
        }
    }
}

