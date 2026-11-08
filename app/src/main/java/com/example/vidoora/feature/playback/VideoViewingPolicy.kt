package com.example.vidoora.feature.playback

/** Management entry never offers playback, even when a capability was previously remembered. */
object VideoViewingPolicy {
    fun canWatch(viewerEntry: Boolean,canPlay: Boolean)=viewerEntry && canPlay
}
