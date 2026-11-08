package com.vidoora.backend.modules.processing

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.playback.PlaybackPaths
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ThumbnailClaim(val videoId: UUID,val token: UUID,val bucket: String,val master: String) {
    val key: String get()=master.removeSuffix("master.m3u8")+"thumbnail.jpg"
}
internal class ThumbnailRepository(private val connections: (() -> Connection)?=null) {
    private fun <T> transaction(block: (Connection)->T): T {
        val factory=connections ?: return DatabaseFactory.jdbcTransaction(block)
        return factory().use { c -> c.autoCommit=false; try { block(c).also { c.commit() } } catch(e: Throwable) { c.rollback(); throw e } }
    }
    fun claim(): ThumbnailClaim?=transaction { c ->
        c.prepareStatement("""SELECT id,processed_bucket,master_playlist_key FROM videos
            WHERE deleted_at IS NULL AND publication_state='PROCESSED' AND thumbnail_object_key IS NULL AND thumbnail_retry_count<3
            AND processed_bucket IS NOT NULL AND master_playlist_key IS NOT NULL AND thumbnail_next_attempt_at<=NOW()
            AND (thumbnail_worker_id IS NULL OR thumbnail_lease_until IS NULL OR thumbnail_lease_until<NOW())
            ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1""").use { s -> s.executeQuery().use { row ->
            if(!row.next()) return@transaction null
            val claim=ThumbnailClaim(row.getObject(1,UUID::class.java),UUID.randomUUID(),row.getString(2),row.getString(3))
            c.prepareStatement("UPDATE videos SET thumbnail_worker_id=?,thumbnail_lease_until=NOW()+INTERVAL '2 minutes',thumbnail_retry_count=thumbnail_retry_count+1 WHERE id=?").use {
                it.setObject(1,claim.token); it.setObject(2,claim.videoId); it.executeUpdate()
            }
            claim
        } }
    }
    fun heartbeat(claim: ThumbnailClaim): Boolean=transaction { c ->
        c.prepareStatement("UPDATE videos SET thumbnail_lease_until=NOW()+INTERVAL '2 minutes' WHERE id=? AND thumbnail_worker_id=? AND thumbnail_lease_until>NOW() AND thumbnail_object_key IS NULL AND deleted_at IS NULL AND master_playlist_key=?").use {
            it.setObject(1,claim.videoId); it.setObject(2,claim.token); it.setString(3,claim.master); it.executeUpdate()==1
        }
    }
    fun complete(claim: ThumbnailClaim): Boolean=transaction { c ->
        c.prepareStatement("UPDATE videos SET thumbnail_object_key=?,thumbnail_worker_id=NULL,thumbnail_lease_until=NULL WHERE id=? AND thumbnail_worker_id=? AND thumbnail_lease_until>NOW() AND master_playlist_key=? AND processed_bucket=? AND publication_state='PROCESSED' AND thumbnail_object_key IS NULL AND deleted_at IS NULL").use {
            it.setString(1,claim.key); it.setObject(2,claim.videoId); it.setObject(3,claim.token); it.setString(4,claim.master); it.setString(5,claim.bucket); it.executeUpdate()==1
        }
    }
    fun fail(claim: ThumbnailClaim)=transaction { c ->
        c.prepareStatement("UPDATE videos SET thumbnail_worker_id=NULL,thumbnail_lease_until=NULL,thumbnail_next_attempt_at=NOW()+INTERVAL '1 minute' WHERE id=? AND thumbnail_worker_id=?").use {
            it.setObject(1,claim.videoId); it.setObject(2,claim.token); it.executeUpdate()
        }
    }
}

/** Backfill only a bounded first HLS segment, never redownload or reprocess the full original. */
internal class ThumbnailWorker(private val repo: ThumbnailRepository,private val storage: S3StorageService,
    private val scratch: Path,private val makeImage: (Path,Path,()->Boolean)->Unit) {
    fun runOne(): ProcessingRunResult {
        Files.createDirectories(scratch)
        check(Files.getFileStore(scratch).usableSpace>64L*1024*1024) { "Thumbnail processing requires 64 MiB scratch space" }
        val claim=repo.claim() ?: return ProcessingRunResult.NO_JOB
        val alive=AtomicBoolean(true)
        val heartbeat=Executors.newSingleThreadScheduledExecutor()
        heartbeat.scheduleAtFixedRate({ try { if(!repo.heartbeat(claim)) alive.set(false) } catch (_: Exception) { alive.set(false) } },20,20,TimeUnit.SECONDS)
        var work: Path?=null
        try {
            check(Regex("processed/${claim.videoId}/[0-9a-f-]{36}/master\\.m3u8").matches(claim.master))
            val prefix=claim.master.removeSuffix("master.m3u8")
            val master=storage.readProcessed(claim.bucket,claim.master,256*1024).toString(Charsets.UTF_8)
            PlaybackPaths.validateManifest("master.m3u8",master)
            val variant=master.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.minBy { it.substringBefore('p').toInt() }
            val playlist=storage.readProcessed(claim.bucket,prefix+variant,256*1024).toString(Charsets.UTF_8)
            PlaybackPaths.validateManifest(variant,playlist)
            val first=playlist.lineSequence().first { it.isNotBlank() && !it.startsWith('#') }
            val segment=storage.readProcessed(claim.bucket,prefix+variant.substringBeforeLast('/')+"/"+first,16*1024*1024)
            if(!alive.get() || !repo.heartbeat(claim)) throw LeaseLost()
            work=Files.createTempDirectory(scratch,"vidoora-attempt-thumbnail-")
            val input=work.resolve("first.ts"); Files.write(input,segment)
            val image=work.resolve("thumbnail.jpg")
            makeImage(input,image) { alive.get() && !Thread.currentThread().isInterrupted }
            check(Files.size(image) in 4..(256*1024).toLong())
            if(!alive.get() || !repo.heartbeat(claim)) throw LeaseLost()
            // Deterministic key under the immutable committed HLS attempt; repeat writes are idempotent.
            storage.uploadProcessed(claim.bucket,claim.key,image)
            check(repo.complete(claim)) { "Thumbnail lease lost" }
            println("Private thumbnail prepared")
            return ProcessingRunResult.COMPLETED
        } catch (_: Exception) {
            try { repo.fail(claim) } catch (_: Exception) { }
            println("Thumbnail deferred; video playback is unchanged")
            return ProcessingRunResult.FAILED
        } finally {
            alive.set(false); heartbeat.shutdownNow(); heartbeat.awaitTermination(10,TimeUnit.SECONDS)
            work?.let { ProcessingWorker.removeAttemptDirectory(scratch,it) }
        }
    }
}

