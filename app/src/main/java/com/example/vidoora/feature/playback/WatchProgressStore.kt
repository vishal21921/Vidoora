package com.example.vidoora.feature.playback

import com.example.vidoora.core.security.SecureStorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class WatchCheckpoint(val positionMs: Long,val durationMs: Long,val updatedAt: Long)
@Serializable private data class WatchProgressFile(val version: Int=1,val entries: Map<String,WatchCheckpoint> = emptyMap())
data class WatchProgressLookup(val checkpoint: WatchCheckpoint?,val available: Boolean=true)
class WatchProgressHandle internal constructor(internal val key: String,internal val identity: String,internal val nonce: UUID)

object WatchProgressPolicy {
    const val MAX_AGE_MS=30L*24*60*60*1000
    const val MAX_DURATION_MS=7L*24*60*60*1000
    fun valid(record: WatchCheckpoint,now: Long): Boolean = record.durationMs in 1..MAX_DURATION_MS &&
        record.positionMs in 0..record.durationMs && record.updatedAt in (now-MAX_AGE_MS)..now
    fun resumable(position: Long,duration: Long)=duration in 1..MAX_DURATION_MS && position>=5_000 && duration-position>5_000
    fun compatible(saved: Long,actual: Long)=saved in 1..MAX_DURATION_MS && actual in 1..MAX_DURATION_MS && kotlin.math.abs(saved-actual)<=maxOf(3_000,actual/20)
    fun candidate(record: WatchCheckpoint?,actual: Long,now: Long=System.currentTimeMillis()): Long? = record?.takeIf {
        valid(it,now) && resumable(it.positionMs,it.durationMs) && compatible(it.durationMs,actual) && resumable(it.positionMs,actual)
    }?.positionMs
    fun time(position: Long): String {
        val seconds=position.coerceIn(0,MAX_DURATION_MS)/1000
        return if(seconds>=3600) "%d:%02d:%02d".format(java.util.Locale.ROOT,seconds/3600,seconds/60%60,seconds%60)
            else "%d:%02d".format(java.util.Locale.ROOT,seconds/60,seconds%60)
    }
}

/** Encrypted numeric checkpoints only; no links, titles, capabilities or navigable history. */
@Singleton
class WatchProgressStore @Inject constructor(private val storage: SecureStorageManager) {
    private val mutex=Mutex()
    private val writes=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val leases=linkedMapOf<String,UUID>()
    private fun key(identity: String,video: String): String {
        require(identity=="guest" || (identity.startsWith("user:") && UUID.fromString(identity.removePrefix("user:")).toString()==identity.removePrefix("user:")))
        require(video.length==36 && UUID.fromString(video).toString()==video.lowercase())
        return MessageDigest.getInstance("SHA-256").digest("$identity|${video.lowercase()}".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun current(handle: WatchProgressHandle)=leases[handle.key]==handle.nonce && storage.watchIdentity()==handle.identity
    private fun parse(raw: String?): WatchProgressFile {
        if(raw==null) return WatchProgressFile()
        require(raw.length<=65_536)
        return Json.decodeFromString<WatchProgressFile>(raw).also { require(it.version==1 && it.entries.size<=100 && it.entries.keys.all { key -> Regex("[0-9a-f]{64}").matches(key) }) }
    }
    suspend fun begin(identity: String,video: String): WatchProgressHandle? = withContext(Dispatchers.IO) { mutex.withLock {
        if(storage.watchIdentity()!=identity) return@withLock null
        val key=key(identity,video); val nonce=UUID.randomUUID()
        leases.remove(key); leases[key]=nonce
        if(leases.size>128) leases.remove(leases.keys.first())
        WatchProgressHandle(key,identity,nonce)
    } }
    suspend fun load(handle: WatchProgressHandle,now: Long=System.currentTimeMillis()): WatchProgressLookup = withContext(Dispatchers.IO) { mutex.withLock {
        if(!current(handle)) return@withLock WatchProgressLookup(null,false)
        try { WatchProgressLookup(parse(storage.readWatchProgress(handle.identity)).entries[handle.key]?.takeIf { WatchProgressPolicy.valid(it,now) }) }
        catch (_: Exception) { WatchProgressLookup(null,false) }
    } }
    suspend fun save(handle: WatchProgressHandle,position: Long,duration: Long,ended: Boolean=false,now: Long=System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if(!current(handle)) return@withLock false
        // Unknown native timelines must not erase a valid earlier checkpoint.
        if(duration !in 1..WatchProgressPolicy.MAX_DURATION_MS || (!ended && position !in 0..duration)) return@withLock false
        try {
            val existing=try { parse(storage.readWatchProgress(handle.identity)).entries } catch (_: Exception) { emptyMap() }
            val entries=existing.filterValues { WatchProgressPolicy.valid(it,now) }.toMutableMap()
            entries.remove(handle.key)
            if(!ended && WatchProgressPolicy.resumable(position,duration)) entries[handle.key]=WatchCheckpoint(position,duration,now)
            val bounded=entries.entries.toList().asReversed().sortedByDescending { it.value.updatedAt }.take(100).associate { it.key to it.value }
            storage.writeWatchProgress(handle.identity,Json.encodeToString(WatchProgressFile(entries=bounded)))
        } catch (_: Exception) { false }
    } }
    suspend fun clear(handle: WatchProgressHandle): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if(!current(handle)) return@withLock false
        try {
            val existing=try { parse(storage.readWatchProgress(handle.identity)).entries } catch (_: Exception) { emptyMap() }
            storage.writeWatchProgress(handle.identity,Json.encodeToString(WatchProgressFile(entries=existing-handle.key)))
        } catch (_: Exception) { false }
    } }
    fun saveAsync(handle: WatchProgressHandle,position: Long,duration: Long,ended: Boolean,onResult: (Boolean)->Unit={}): Job = writes.launch {
        onResult(save(handle,position,duration,ended))
    }
}


