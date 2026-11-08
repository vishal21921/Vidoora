package com.example.vidoora.feature.ads

/** One explicit Watch. A late load may never show after timeout/navigation/account change. */
class WatchAdGate<Ad>(private val scheduleTimeout: (() -> Unit)->(() -> Unit),private val busy: (Boolean)->Unit={}) {
    private class Attempt { var done=false; var showing=false; var cancelTimer: ()->Unit={} }
    private var current: Attempt?=null
    val pending get()=current!=null
    fun cancel() {
        val attempt=current ?: return
        attempt.done=true; attempt.cancelTimer(); current=null; busy(false)
    }
    fun open(eligible: Boolean,valid: ()->Boolean,load: ((Ad?)->Unit)->Unit,
             show: (Ad,()->Unit)->Unit,proceed: ()->Unit) {
        if(pending || !valid()) return
        if(!eligible) { proceed(); return }
        val attempt=Attempt(); current=attempt; busy(true)
        fun finish() {
            if(attempt.done) return
            attempt.done=true; attempt.cancelTimer()
            if(current===attempt) { current=null; busy(false) }
            if(valid()) proceed()
        }
        attempt.cancelTimer=scheduleTimeout { if(!attempt.showing) finish() }
        try { load { ad ->
            if(!attempt.done && !attempt.showing) {
                if(!valid()) { if(current===attempt) cancel() }
                else if(ad==null) finish()
                else {
                    attempt.showing=true; attempt.cancelTimer()
                    try { show(ad,::finish) } catch (_: Exception) { finish() }
                }
            }
        } } catch (_: Exception) { finish() }
    }
}
