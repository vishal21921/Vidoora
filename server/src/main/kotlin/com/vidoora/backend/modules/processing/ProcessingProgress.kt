package com.vidoora.backend.modules.processing

/** Scoped to the synchronous worker thread; no cross-job/global progress. */
internal object ProcessingProgress {
    private val sink=ThreadLocal<(Int)->Unit>()
    fun report(percent: Int) {sink.get()?.invoke(percent.coerceIn(0,99))}
    fun <T> track(callback: (Int)->Unit,block: ()->T): T {
        sink.set(callback)
        try {return block()} finally {sink.remove()}
    }
    fun percent(micros: Long,duration: Double,index: Int,total: Int): Int =
        ((index+(micros/1_000_000.0/duration).coerceIn(0.0,1.0))/total*85).toInt().coerceIn(0,85)
}
