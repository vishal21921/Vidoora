package com.example.vidoora.feature.playback

import kotlinx.coroutines.*

/** Foreground only; no network when idle. One request at a time with 5–60 second failure backoff. */
class ProcessingStatusPoller(private val scope: CoroutineScope,private val needed: ()->Boolean,private val update: suspend ()->Boolean) {
    private var job: Job?=null
    fun start() {
        if(job?.isActive==true) return
        job=scope.launch {
            var wait=5_000L
            while(isActive) {
                delay(wait)
                wait=if(!needed()) 5_000L else {
                    val success=try { update() } catch(e: CancellationException) { throw e } catch (_: Exception) { false }
                    if(success) 5_000L else (wait*2).coerceAtMost(60_000L)
                }
            }
        }
    }
    fun stop() { job?.cancel(); job=null }
}

object VideoProcessingStatus {
    fun pending(state: String)=state in setOf("UPLOADING","UPLOADED","PROCESSING")
    fun label(state: String,owner: Boolean=true): String = when(state) {
        "UPLOADING" -> "Uploading"
        "UPLOADED" -> "Waiting for processing"
        "PROCESSING" -> "Processing video"
        "PROCESSED" -> if(owner) "Ready to share" else "Ready to watch"
        "PROCESSING_FAILED" -> "Processing failed"
        "CANCELLED" -> "Upload cancelled"
        "DELETED" -> "Video unavailable"
        else -> "Status unavailable"
    }
    fun guidance(state: String): String? = when(state) {
        "UPLOADING" -> "The file is still uploading. Processing begins after the upload finishes."
        "UPLOADED" -> "Upload complete. Keep the video processor running to prepare playback. Status updates automatically while this screen is open."
        "PROCESSING" -> "Preparing playback versions. Larger videos take longer; status updates automatically."
        "PROCESSING_FAILED" -> "The video could not be prepared. Check the processor log. You can try uploading a valid video again."
        else -> null
    }
}
