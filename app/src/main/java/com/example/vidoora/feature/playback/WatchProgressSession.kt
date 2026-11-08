package com.example.vidoora.feature.playback

import com.example.vidoora.core.network.dto.ApiEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Numeric UI state only; never save a capability or an authorization decision to disk. */
data class WatchStartState(val loading: Boolean=true,val promptPositionMs: Long?=null,val startPositionMs: Long?=null,
    val error: String?=null,val warning: String?=null,val unavailable: Boolean=false,val viewer: Boolean=false,val durationMs: Long=0,
    val attempt: Int=0)

class WatchProgressSession(private val client: HttpClient,private val store: WatchProgressStore,
    private val identity: String,private val currentIdentity: ()->String?,private val videoId: String,private val shareToken: String?,
    private val history: WatchHistoryStore? = null) {
    private val mutable=MutableStateFlow(WatchStartState())
    val state=mutable.asStateFlow()
    private var handle: WatchProgressHandle?=null
    private var candidate: WatchCheckpoint?=null
    private var mediaReady=false
    private var historyTitle: String? = null
    private var generation=0
    private val opening=java.util.concurrent.atomic.AtomicBoolean(false)
    suspend fun open(retryPosition: Long?=null) {
        if(!opening.compareAndSet(false,true)) return
        val version=++generation
        mediaReady=false; mutable.value=WatchStartState()
        try {
            val response=client.get("api/v1/videos/$videoId/details") { shareToken?.let { header("X-Vidoora-Share",it) } }
            if(version!=generation || currentIdentity()!=identity) return
            if(response.status.value!=200) {
                mutable.value=WatchStartState(loading=false,unavailable=response.status.value in setOf(401,403,404),error=if(response.status.value in setOf(401,403,404)) "Video unavailable. Check the link and your current access." else "Cannot check video access. Please retry.")
                return
            }
            val result=response.body<ApiEnvelope<VideoDetailsDto>>()
            if(version!=generation || currentIdentity()!=identity) return
            val video=result.data
            if(!result.success || video?.id!=videoId || !video.canPlay) {
                mutable.value=WatchStartState(loading=false,unavailable=true,error="Video unavailable or not ready to watch.")
                return
            }
            historyTitle = video.title
            // Only a fresh successful server check permits looking up a saved position.
            handle=store.begin(identity,videoId)
            val lookup=handle?.let { store.load(it) } ?: WatchProgressLookup(null,false)
            if(version!=generation || currentIdentity()!=identity) return
            candidate=if(retryPosition==null) lookup.checkpoint else null
            val position=WatchProgressPolicy.candidate(candidate,video.durationSeconds.toLong()*1000)
            mutable.value=WatchStartState(loading=false,promptPositionMs=if(retryPosition==null) position else null,
                startPositionMs=if(retryPosition!=null) retryPosition.coerceAtLeast(0) else if(position==null) 0 else null,
                warning=if(lookup.available) null else "Saved watch progress is unavailable. Playback still works.",viewer=true,
                durationMs=video.durationSeconds.toLong()*1000,attempt=version)
        } catch(e: CancellationException) { throw e }
        catch (_: Exception) { if(version==generation && currentIdentity()==identity) mutable.value=WatchStartState(loading=false,error="Cannot open this video. Check your connection and retry.") }
        finally { opening.set(false) }
    }
    fun resume() {
        val position=mutable.value.promptPositionMs ?: return
        if(currentIdentity()!=identity) return
        mutable.value=mutable.value.copy(promptPositionMs=null,startPositionMs=position)
    }
    suspend fun startOver() {
        if(mutable.value.promptPositionMs==null || currentIdentity()!=identity) return
        mutable.value=mutable.value.copy(loading=true,promptPositionMs=null)
        val success=handle?.let { store.clear(it) } ?: false
        if(currentIdentity()!=identity) return
        candidate=null
        mutable.update { it.copy(loading=false,startPositionMs=0,warning=if(success) null else "Could not clear saved progress. Playback starts from the beginning.") }
    }
    fun timelinePosition(position: Long,duration: Long): Long {
        if(duration !in 1..WatchProgressPolicy.MAX_DURATION_MS) return 0
        val old=candidate
        if(old!=null && (!WatchProgressPolicy.compatible(old.durationMs,duration) || !WatchProgressPolicy.resumable(position,duration))) return 0
        return position.takeIf { it in 0 until duration } ?: 0
    }
    fun mediaPrepared() { mediaReady=mutable.value.startPositionMs!=null && mutable.value.error==null && currentIdentity()==identity }
    fun mediaFailed() { mediaReady=false }
    fun contentPlaying() {
        if (!mediaReady || currentIdentity() != identity) return
        val title = historyTitle ?: return
        historyTitle = null
        history?.record(identity, videoId, title, shareToken)
    }
    fun checkpoint(position: Long,duration: Long,ended: Boolean=false): Job? {
        val current=handle ?: return null
        if(!mediaReady || currentIdentity()!=identity) return null
        return store.saveAsync(current,position,duration,ended) { success ->
            if(!success && handle===current && currentIdentity()==identity) mutable.update { it.copy(warning="Watch progress could not be saved. Playback still works.") }
        }
    }
}






