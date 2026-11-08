package com.example.vidoora.feature.playback

import com.example.vidoora.core.security.SecureStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WatchedVideo(val id: String, val title: String, val watchedAt: Long, val capability: String? = null) {
    override fun toString(): String = "WatchedVideo(<redacted>)"
}

@Singleton
class WatchHistoryStore @Inject constructor(private val storage: SecureStorageManager) {
    private val mutex = Mutex()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    private fun read(identity: String, now: Long): List<WatchedVideo> =
        storage.readWatchHistory(identity)?.let { Json.decodeFromString<List<WatchedVideo>>(it) }.orEmpty()
            .filter { it.watchedAt in (now - WatchProgressPolicy.MAX_AGE_MS)..now }

    suspend fun load(identity: String): List<WatchedVideo> = withContext(Dispatchers.IO) {
        mutex.withLock { read(identity, System.currentTimeMillis()) }
    }

    fun record(identity: String, id: String, title: String, capability: String?) {
        val now = System.currentTimeMillis()
        val entry = WatchedVideo(id, title.take(128), now, capability?.takeIf { it.length <= 4096 })
        writer.launch {
            mutex.withLock {
                runCatching {
                    val current = read(identity, System.currentTimeMillis())
                    val entries = (listOf(entry) + current).sortedByDescending { it.watchedAt }
                        .distinctBy { it.id }.take(50)
                    storage.writeWatchHistory(identity, Json.encodeToString(entries))
                    revision.value++
                }
            }
        }
    }

    suspend fun remove(identity: String, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read(identity, System.currentTimeMillis()).filterNot { it.id == id }
            storage.writeWatchHistory(identity, Json.encodeToString(entries))
            revision.value++
        }
    }
}
