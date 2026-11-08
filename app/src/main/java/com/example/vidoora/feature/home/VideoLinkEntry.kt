package com.example.vidoora.feature.home

import com.example.vidoora.core.network.SharedVideoLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VideoLinkInput(val text: String="",val invalid: Boolean=false) {
    override fun toString()="VideoLinkInput(text=<redacted>, invalid=$invalid)"
}

/** Debounced, memory-only input. A newer edit cancels the pending open. */
class VideoLinkEntry(private val scope: CoroutineScope,private val onOpen: (SharedVideoLink)->Unit) {
    private val mutable=MutableStateFlow(VideoLinkInput())
    val state=mutable.asStateFlow()
    private var pending: Job?=null
    fun change(value: String) {
        pending?.cancel()
        val text=value.take(4601)
        mutable.value=VideoLinkInput(text)
        if(text.isBlank()) return
        pending=scope.launch {
            delay(400)
            val link=SharedVideoLink.parseShared(text)
            if(link==null) mutable.value=VideoLinkInput(text,invalid=true) else {
                mutable.value=VideoLinkInput()
                onOpen(link)
            }
        }
    }
}
