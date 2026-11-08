package com.example.vidoora.feature.ads

import com.example.vidoora.core.security.SecureStorageManager
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.plugins.timeout
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable private data class AdActivityEvent(val requestId: String, val deviceKey: String, val format: String, val event: String)

/** Bounded best-effort telemetry: no ad identifiers, URLs, emails or playback dependency. */
@Singleton class AdActivityRecorder @Inject constructor(private val client: HttpClient, private val storage: SecureStorageManager) {
    private val queue = Channel<AdActivityEvent>(128)
    init {
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            for(event in queue) {
                for(attempt in 0..2) {
                    val success = try {
                        client.post("api/v1/analytics/ads/event") {
                            timeout { requestTimeoutMillis=3000; connectTimeoutMillis=2000; socketTimeoutMillis=3000 }
                            setBody(event)
                        }.status.value.let { it==202 || it in 400..499 }
                    } catch(error: CancellationException) { throw error } catch(_: Exception) { false }
                    if(success) break
                    delay(250L*(attempt+1))
                }
            }
        }
    }
    fun request(format: String): String = UUID.randomUUID().toString().also { event(it,format,"REQUEST") }
    fun event(id: String,format: String,event: String) {
        runCatching { queue.trySend(AdActivityEvent(id,storage.analyticsViewerKey(),format,event)) }
    }
}
