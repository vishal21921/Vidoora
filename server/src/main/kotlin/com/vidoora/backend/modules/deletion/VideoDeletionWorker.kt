package com.vidoora.backend.modules.deletion

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.infrastructure.S3StorageService
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DeletionPaths {
    fun checkRaw(videoId: UUID,key: String?) {
        require(key==null || Regex("raw/$videoId\\.(mp4|mov|mkv|webm)").matches(key)) { "Invalid deletion object binding" }
    }
}
data class DeletionClaim(val videoId: UUID,val token: UUID,val rawKey: String?,val buckets: List<String>)
interface VideoDeletionRepository {
    fun claim(): DeletionClaim?
    fun heartbeat(claim: DeletionClaim): Boolean
    fun complete(claim: DeletionClaim): Boolean
    fun fail(claim: DeletionClaim)
}
class PostgresVideoDeletionRepository(private val defaultBucket: String,private val connections: (() -> Connection)?=null) : VideoDeletionRepository {
    private fun <T> transaction(block: (Connection)->T): T {
        val factory=connections ?: return DatabaseFactory.jdbcTransaction(block)
        return factory().use { c -> c.autoCommit=false; try { block(c).also { c.commit() } } catch(e: Throwable) { c.rollback(); throw e } }
    }
    override fun claim(): DeletionClaim?=transaction { c ->
        c.prepareStatement("""SELECT id,raw_object_key,processed_bucket FROM videos WHERE deleted_at IS NOT NULL AND storage_deleted_at IS NULL
            AND deletion_cleanup_next_at<=NOW() AND (deletion_cleanup_token IS NULL OR deletion_cleanup_lease_until<NOW())
            ORDER BY deletion_cleanup_next_at FOR UPDATE SKIP LOCKED LIMIT 1""").use { s -> s.executeQuery().use { row ->
            if(!row.next()) return@transaction null
            val id=row.getObject(1,UUID::class.java); val token=UUID.randomUUID()
            val buckets=mutableListOf(defaultBucket); row.getString(3)?.let { buckets.add(it) }
            c.prepareStatement("SELECT DISTINCT a.bucket FROM processing_attempts a JOIN processing_jobs j ON j.id=a.job_id WHERE j.video_id=?").use {
                it.setObject(1,id); it.executeQuery().use { attempts -> while(attempts.next()) buckets.add(attempts.getString(1)) }
            }
            c.prepareStatement("UPDATE videos SET deletion_cleanup_token=?,deletion_cleanup_lease_until=NOW()+INTERVAL '5 minutes',deletion_cleanup_attempts=LEAST(100000,deletion_cleanup_attempts+1) WHERE id=?").use {
                it.setObject(1,token); it.setObject(2,id); it.executeUpdate()
            }
            DeletionClaim(id,token,row.getString(2),buckets.distinct())
        } }
    }
    override fun heartbeat(claim: DeletionClaim): Boolean=transaction { c ->
        c.prepareStatement("UPDATE videos SET deletion_cleanup_lease_until=NOW()+INTERVAL '5 minutes' WHERE id=? AND deletion_cleanup_token=? AND deletion_cleanup_lease_until>NOW() AND deleted_at IS NOT NULL AND storage_deleted_at IS NULL").use {
            it.setObject(1,claim.videoId); it.setObject(2,claim.token); it.executeUpdate()==1
        }
    }
    override fun complete(claim: DeletionClaim): Boolean=transaction { c ->
        c.prepareStatement("""UPDATE videos SET storage_deleted_at=NOW(),deletion_cleanup_token=NULL,deletion_cleanup_lease_until=NULL,
            raw_object_key=NULL,master_playlist_key=NULL,master_playlist_url=NULL,thumbnail_object_key=NULL,thumbnail_url=NULL,processed_bucket=NULL,duration_seconds=0
            WHERE id=? AND deletion_cleanup_token=? AND deletion_cleanup_lease_until>NOW() AND deleted_at IS NOT NULL AND storage_deleted_at IS NULL""").use {
            it.setObject(1,claim.videoId); it.setObject(2,claim.token); it.executeUpdate()==1
        }
    }
    override fun fail(claim: DeletionClaim) { transaction { c ->
        c.prepareStatement("UPDATE videos SET deletion_cleanup_token=NULL,deletion_cleanup_lease_until=NULL,deletion_cleanup_next_at=NOW()+INTERVAL '1 minute'*LEAST(60,POWER(2,LEAST(deletion_cleanup_attempts,6))) WHERE id=? AND deletion_cleanup_token=?").use {
            it.setObject(1,claim.videoId); it.setObject(2,claim.token); it.executeUpdate()
        }
    } }
}
class VideoDeletionWorker(private val repo: VideoDeletionRepository,private val storage: S3StorageService) {
    fun runOne(): Boolean {
        val claim=repo.claim() ?: return false
        val alive=AtomicBoolean(true); val heartbeat=Executors.newSingleThreadScheduledExecutor()
        heartbeat.scheduleAtFixedRate({ try { if(!repo.heartbeat(claim)) alive.set(false) } catch (_: Exception) { alive.set(false) } },20,20,TimeUnit.SECONDS)
        try {
            DeletionPaths.checkRaw(claim.videoId,claim.rawKey)
            check(repo.heartbeat(claim))
            storage.deleteVideoObjects(claim.videoId,claim.rawKey,claim.buckets) { alive.get() && !Thread.currentThread().isInterrupted }
            check(alive.get() && repo.complete(claim))
            println("Deleted video storage cleanup completed")
        } catch (_: Exception) {
            try { repo.fail(claim) } catch (_: Exception) { }
            println("Deleted video storage cleanup deferred")
        } finally { alive.set(false); heartbeat.shutdownNow(); heartbeat.awaitTermination(10,TimeUnit.SECONDS) }
        return true
    }
}
