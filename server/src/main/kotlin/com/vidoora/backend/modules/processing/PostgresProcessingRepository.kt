package com.vidoora.backend.modules.processing

import com.vidoora.backend.infrastructure.DatabaseFactory
import java.sql.Connection
import java.util.UUID

/** All claims/completions are fenced by a unique attempt token and an unexpired database lease. */
class PostgresProcessingRepository(private val connectionFactory: (() -> Connection)? = null) : ProcessingRepository {
    private fun <T> transaction(block: (Connection) -> T): T {
        val factory = connectionFactory ?: return DatabaseFactory.jdbcTransaction(block)
        return factory().use { c ->
            c.autoCommit = false
            try { block(c).also { c.commit() } }
            catch (e: Throwable) { c.rollback(); throw e }
        }
    }
    override fun claim(bucket: String): ProcessingClaim? = transaction { c ->
        // Lock expired rows before changing ownership. Multiple workers can safely run this sweep.
        c.prepareStatement("SELECT id FROM processing_jobs WHERE status='PROCESSING' AND lease_until < NOW() FOR UPDATE SKIP LOCKED").use { s ->
            s.executeQuery().use { rows ->
                val ids = mutableListOf<UUID>()
                while (rows.next()) ids += rows.getObject(1, UUID::class.java)
                for (id in ids) {
                    c.prepareStatement("UPDATE processing_attempts SET state='CLEANUP',cleanup_after=NOW()+INTERVAL '1 hour' WHERE job_id=? AND state='ACTIVE'").use { it.setObject(1,id); it.executeUpdate() }
                    c.prepareStatement("UPDATE processing_jobs SET retry_count=retry_count+1,status=CASE WHEN retry_count < max_retries THEN 'QUEUED' ELSE 'FAILED' END,worker_id=NULL,lease_until=NULL,next_attempt_at=NOW()+INTERVAL '1 minute',error_message='Worker interrupted' WHERE id=?").use { it.setObject(1,id); it.executeUpdate() }
                    c.prepareStatement("UPDATE videos SET publication_state='PROCESSING_FAILED',updated_at=NOW() WHERE id=(SELECT video_id FROM processing_jobs WHERE id=? AND status='FAILED') AND publication_state='PROCESSING' AND deleted_at IS NULL").use { it.setObject(1,id); it.executeUpdate() }
                }
            }
        }
        c.prepareStatement("""
            SELECT j.id,j.video_id,v.raw_object_key,s.total_bytes
            FROM processing_jobs j JOIN videos v ON v.id=j.video_id
            JOIN upload_sessions s ON s.video_id=v.id AND s.status='COMPLETED'
            WHERE j.status='QUEUED' AND j.next_attempt_at<=NOW()
              AND v.deleted_at IS NULL AND v.publication_state IN ('UPLOADED','PROCESSING') AND v.raw_object_key IS NOT NULL
            ORDER BY j.created_at FOR UPDATE OF j,v SKIP LOCKED LIMIT 1
        """.trimIndent()).use { stmt ->
            stmt.executeQuery().use { row ->
                if (!row.next()) return@transaction null
                val token = UUID.randomUUID()
                val job = row.getObject(1,UUID::class.java)
                val video = row.getObject(2,UUID::class.java)
                val result = ProcessingClaim(job,video,token,row.getString(3),row.getLong(4),bucket,"processed/$video/$token/")
                c.prepareStatement("UPDATE processing_jobs SET progress_percent=0,status='PROCESSING',worker_id=?,lease_until=NOW()+INTERVAL '2 minutes',started_at=NOW(),completed_at=NULL,error_message=NULL WHERE id=?").use { it.setString(1,token.toString()); it.setObject(2,job); it.executeUpdate() }
                c.prepareStatement("UPDATE videos SET publication_state='PROCESSING',updated_at=NOW() WHERE id=?").use { it.setObject(1,video); it.executeUpdate() }
                c.prepareStatement("INSERT INTO processing_attempts(id,job_id,bucket,object_prefix) VALUES (?,?,?,?)").use { it.setObject(1,token); it.setObject(2,job); it.setString(3,bucket); it.setString(4,result.prefix); it.executeUpdate() }
                result
            }
        }
    }
    override fun progress(claim: ProcessingClaim,percent: Int) { transaction { c ->
        c.prepareStatement("UPDATE processing_jobs SET progress_percent=? WHERE id=? AND worker_id=? AND status='PROCESSING' AND lease_until>NOW()").use {
            it.setInt(1,percent.coerceIn(0,99));it.setObject(2,claim.jobId);it.setString(3,claim.token.toString());it.executeUpdate()
        }
    } }
    override fun heartbeat(claim: ProcessingClaim): Boolean = transaction { c ->
        c.prepareStatement("UPDATE processing_jobs SET lease_until=NOW()+INTERVAL '2 minutes' WHERE id=? AND worker_id=? AND status='PROCESSING' AND lease_until>NOW()").use {
            it.setObject(1,claim.jobId); it.setString(2,claim.token.toString()); it.executeUpdate()==1
        }
    }
    private fun owned(c: Connection, claim: ProcessingClaim): Boolean = c.prepareStatement("SELECT id FROM processing_jobs WHERE id=? AND worker_id=? AND status='PROCESSING' AND lease_until>NOW() FOR UPDATE").use {
        it.setObject(1,claim.jobId); it.setString(2,claim.token.toString()); it.executeQuery().use { row -> row.next() }
    }
    override fun complete(claim: ProcessingClaim, output: ProcessingOutput): Boolean = transaction { c ->
        if (!owned(c,claim)) return@transaction false
        c.prepareStatement("DELETE FROM video_renditions WHERE video_id=?").use { it.setObject(1,claim.videoId); it.executeUpdate() }
        for (r in output.renditions) c.prepareStatement("INSERT INTO video_renditions(id,video_id,resolution,bitrate_bps,codec,playlist_url,playlist_object_key) VALUES (?,?,?,?,'h264',?,?)").use {
            val key = claim.prefix + r.name + "/index.m3u8"
            it.setObject(1,UUID.randomUUID()); it.setObject(2,claim.videoId); it.setString(3,"${r.width}x${r.height}"); it.setInt(4,r.bitrate)
            it.setString(5,"r2://${claim.bucket}/$key"); it.setString(6,key); it.executeUpdate()
        }
        c.prepareStatement("UPDATE videos SET publication_state='PROCESSED',duration_seconds=?,processed_bucket=?,master_playlist_key=?,master_playlist_url=NULL,thumbnail_object_key=?,thumbnail_worker_id=NULL,thumbnail_lease_until=NULL,updated_at=NOW() WHERE id=?").use {
            it.setInt(1,kotlin.math.ceil(output.media.duration).toInt()); it.setString(2,claim.bucket); it.setString(3,claim.prefix+"master.m3u8"); it.setString(4,if(java.nio.file.Files.isRegularFile(output.directory.resolve("thumbnail.jpg"))) claim.prefix+"thumbnail.jpg" else null); it.setObject(5,claim.videoId); it.executeUpdate()
        }
        c.prepareStatement("UPDATE processing_jobs SET progress_percent=100,status='COMPLETED',completed_at=NOW(),lease_until=NULL,error_message=NULL WHERE id=?").use { it.setObject(1,claim.jobId); it.executeUpdate() }
        c.prepareStatement("UPDATE processing_attempts SET state='COMMITTED' WHERE id=?").use { it.setObject(1,claim.token); it.executeUpdate() }
        true
    }
    override fun fail(claim: ProcessingClaim, permanent: Boolean): Boolean = transaction { c ->
        if (!owned(c,claim)) return@transaction false
        c.prepareStatement("UPDATE processing_jobs SET retry_count=retry_count+1,status=CASE WHEN ? OR retry_count>=max_retries THEN 'FAILED' ELSE 'QUEUED' END,next_attempt_at=NOW()+INTERVAL '1 minute'*LEAST(15,POWER(2,retry_count)),error_message=?,worker_id=NULL,lease_until=NULL WHERE id=?").use {
            it.setBoolean(1,permanent); it.setString(2,if(permanent) "Original unavailable or unsupported/invalid media" else "Processing failed; retry scheduled if attempts remain")
            it.setObject(3,claim.jobId); it.executeUpdate()
        }
        c.prepareStatement("UPDATE videos SET publication_state='PROCESSING_FAILED',updated_at=NOW() WHERE id=? AND EXISTS (SELECT 1 FROM processing_jobs WHERE id=? AND status='FAILED')").use { it.setObject(1,claim.videoId); it.setObject(2,claim.jobId); it.executeUpdate() }
        c.prepareStatement("UPDATE processing_attempts SET state='CLEANUP',cleanup_after=NOW()+INTERVAL '1 hour' WHERE id=?").use { it.setObject(1,claim.token); it.executeUpdate() }
        true
    }
    fun cleanupOne(deletePrefix: (String,String)->Unit): Boolean = transaction { c ->
        c.prepareStatement("SELECT id,bucket,object_prefix FROM processing_attempts WHERE state='CLEANUP' AND cleanup_after<=NOW() FOR UPDATE SKIP LOCKED LIMIT 1").use { s ->
            s.executeQuery().use { row ->
                if (!row.next()) return@transaction false
                val id = row.getObject(1,UUID::class.java)
                deletePrefix(row.getString(2),row.getString(3))
                c.prepareStatement("UPDATE processing_attempts SET state='CLEANED' WHERE id=?").use { it.setObject(1,id); it.executeUpdate() }
                true
            }
        }
    }
}



