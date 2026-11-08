package com.example.vidoora.feature.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.network.dto.ApiEnvelope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable data class SafetyResultDto(val accepted: Boolean)
@Serializable data class BlockedUploaderDto(val id: String,val displayName: String)
@Serializable data class BlockedUploadersPageDto(val items: List<BlockedUploaderDto>,val nextCursor: String?=null)
data class BlockedUploadersState(val loading: Boolean=false,val items: List<BlockedUploaderDto> = emptyList(),val nextCursor: String?=null,val error: String?=null)

@HiltViewModel
class BlockedUploadersViewModel @Inject constructor(private val client: HttpClient) : ViewModel() {
    private val mutable=MutableStateFlow(BlockedUploadersState())
    val state=mutable.asStateFlow()
    init { refresh() }
    fun refresh() { load(false) }
    fun more() { if(mutable.value.nextCursor!=null) load(true) }
    private fun load(more: Boolean) {
        if(mutable.value.loading) return
        viewModelScope.launch {
            val cursor=if(more) mutable.value.nextCursor else null
            mutable.value=mutable.value.copy(loading=true,error=null)
            try {
                val response=client.get("api/v1/users/me/blocked-uploaders") { cursor?.let { parameter("cursor",it) } }
                check(response.status.value==200)
                val page=checkNotNull(response.body<ApiEnvelope<BlockedUploadersPageDto>>().data)
                mutable.value=BlockedUploadersState(items=if(more) (mutable.value.items+page.items).distinctBy { it.id } else page.items,nextCursor=page.nextCursor)
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Cannot load blocked uploaders. Please retry.") }
        }
    }
    fun unblock(id: String) {
        if(mutable.value.loading || mutable.value.items.none { it.id==id }) return
        viewModelScope.launch {
            mutable.value=mutable.value.copy(loading=true,error=null)
            try {
                val response=client.delete("api/v1/users/me/blocked-uploaders/$id")
                check(response.status.value==200 && response.body<ApiEnvelope<SafetyResultDto>>().data?.accepted==true)
                mutable.value=mutable.value.copy(loading=false,items=mutable.value.items.filterNot { it.id==id })
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value=mutable.value.copy(loading=false,error="Unable to unblock. Please retry.") }
        }
    }
}

