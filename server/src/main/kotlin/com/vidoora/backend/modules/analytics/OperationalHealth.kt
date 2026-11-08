package com.vidoora.backend.modules.analytics

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.modules.playback.PlaybackDenied
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable data class ServiceHealth(val checkedAt: String, val processor: String,
    val processorLastSeen: String?, val lastVerifiedBackup: String?, val queued: Long,
    val processing: Long, val failed: Long, val stuck: Long)

class OperationalHealth(private val clock: Clock = Clock.systemUTC()) {
    suspend fun read(user: String): ServiceHealth = withContext(Dispatchers.IO) {
        DatabaseFactory.jdbcTransaction { c ->
            c.prepareStatement("SELECT role FROM users WHERE id=? AND is_suspended=FALSE").use { s ->
                s.setObject(1, UUID.fromString(user))
                s.executeQuery().use { r -> if (!r.next() || r.getString(1) != "ADMIN") throw PlaybackDenied() }
            }
            val seen = mutableMapOf<String, java.time.Instant>()
            c.prepareStatement("SELECT component,last_seen FROM service_operational_status").use { s ->
                s.executeQuery().use { r -> while (r.next()) seen[r.getString(1)] = r.getTimestamp(2).toInstant() }
            }
            val now = clock.instant()
            val processor = seen["processor"]
            val status = when {
                processor == null -> "UNKNOWN"
                processor.isAfter(now.plusSeconds(15)) -> "UNKNOWN"
                processor.isBefore(now.minusSeconds(90)) -> "STALE"
                else -> "RECENT"
            }
            c.prepareStatement("""SELECT
                COUNT(*) FILTER (WHERE status='QUEUED'),
                COUNT(*) FILTER (WHERE status='PROCESSING'),
                COUNT(*) FILTER (WHERE status='FAILED'),
                COUNT(*) FILTER (WHERE status='PROCESSING' AND lease_until < CURRENT_TIMESTAMP)
                FROM processing_jobs""").use { s ->
                s.executeQuery().use { r ->
                    check(r.next())
                    ServiceHealth(now.toString(), status, processor?.toString(), seen["database-backup"]?.toString(),
                        r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4))
                }
            }
        }
    }
}

/** Independent of a job lease: proves only that this processor process can reach PostgreSQL. */
class ProcessorHeartbeat : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "processor-health").apply { isDaemon = true }
    }
    init {
        scheduler.scheduleWithFixedDelay({
            try {
                DatabaseFactory.jdbcTransaction { c ->
                    c.prepareStatement("""INSERT INTO service_operational_status(component,last_seen)
                        VALUES ('processor',CURRENT_TIMESTAMP)
                        ON CONFLICT(component) DO UPDATE SET last_seen=EXCLUDED.last_seen""").use { it.executeUpdate() }
                }
            } catch (_: Exception) { /* A failed heartbeat must never be reported as healthy. */ }
        }, 0, 15, TimeUnit.SECONDS)
    }
    override fun close() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(5, TimeUnit.SECONDS)
    }
}
