package com.example.vidoora.feature.analytics

import com.example.vidoora.core.security.SecureStorageManager
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.plugins.timeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackAnalytics @Inject constructor(private val client: HttpClient, private val storage: SecureStorageManager) {
    private data class Visit(val id: String = UUID.randomUUID().toString(), var opened: Boolean = false,
        var playedMs: Long = 0, var ended: Boolean = false)
    private val mutex = Mutex()
    private val visits = linkedMapOf<String, Visit>()
    private val delivery = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun key(video: String, identity: String) = "$identity|$video"

    suspend fun open(video: String, token: String?, identity: String, newVisit: Boolean = false) = mutex.withLock {
        if (storage.watchIdentity() != identity) return@withLock
        val key = key(video, identity)
        if (newVisit || key !in visits) {
            visits[key] = Visit()
            if (visits.size > 100) visits.remove(visits.keys.first())
        }
        val visit = visits.getValue(key)
        if (!visit.opened) visit.opened = send(video, token, visit, false)
    }

    suspend fun tick(video: String, token: String?, identity: String, playedMs: Long, ended: Boolean) = mutex.withLock {
        if (storage.watchIdentity() != identity) return@withLock
        val visit = visits.getOrPut(key(video, identity)) { Visit() }
        if (!visit.opened) visit.opened = send(video, token, visit, false)
        visit.playedMs = (visit.playedMs + playedMs.coerceIn(0, 600_000)).coerceAtMost(21_600_000)
        visit.ended = visit.ended || ended
        send(video, token, visit, true)
    }

    fun flush(video: String, token: String?, identity: String, playedMs: Long, ended: Boolean) {
        if (playedMs <= 0 && !ended) return
        delivery.launch(start = CoroutineStart.UNDISPATCHED) { tick(video, token, identity, playedMs, ended) }
    }

    private suspend fun send(video: String, token: String?, visit: Visit, started: Boolean): Boolean = try {
        client.post("api/v1/analytics/videos/$video/event") {
            timeout { requestTimeoutMillis = 3000; connectTimeoutMillis = 2000; socketTimeoutMillis = 3000 }
            token?.let { header("X-Vidoora-Share", it) }
            setBody(kotlinx.serialization.json.buildJsonObject {
                put("sessionId", kotlinx.serialization.json.JsonPrimitive(visit.id))
                put("viewerKey", kotlinx.serialization.json.JsonPrimitive(storage.analyticsViewerKey()))
                put("playedMs", kotlinx.serialization.json.JsonPrimitive(visit.playedMs))
                put("started", kotlinx.serialization.json.JsonPrimitive(started))
                put("ended", kotlinx.serialization.json.JsonPrimitive(visit.ended))
            })
        }.status.value == 202
    } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
}
