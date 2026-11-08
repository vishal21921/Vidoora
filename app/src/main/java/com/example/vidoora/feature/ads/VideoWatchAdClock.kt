package com.example.vidoora.feature.ads

/** Monotonic actual viewing time, local to one player; seeks, ad time and playback speed add no credit. */
class VideoWatchAdClock {
    companion object { const val INTERVAL_MS=600_000L }
    private var previous: Long?=null
    private var running=false
    var watchedMs=0L; private set
    fun update(elapsedMs: Long,watchingContent: Boolean) {
        val now=elapsedMs.coerceAtLeast(0)
        val old=previous
        if(running && old!=null && now>=old) watchedMs+=(now-old).coerceAtMost(INTERVAL_MS-watchedMs)
        previous=now; running=watchingContent
    }
    fun reset(elapsedMs: Long) { watchedMs=0; previous=elapsedMs.coerceAtLeast(0); running=false }
    fun takeBreak(remainingContentMs: Long): Boolean {
        if(!running || watchedMs<INTERVAL_MS || remainingContentMs<=10_000) return false
        watchedMs=0
        return true
    }
}
