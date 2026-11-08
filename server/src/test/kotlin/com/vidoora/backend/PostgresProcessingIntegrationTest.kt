package com.vidoora.backend

import com.vidoora.backend.modules.processing.*
import org.flywaydb.core.Flyway
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PostgresProcessingIntegrationTest {
    @Test fun migrationsConcurrentClaimsLeaseFencingAtomicCompletionAndRetries() {
        assumeTrue("Explicit real PostgreSQL opt-in required", System.getenv("VIDOORA_POSTGRES_TEST")=="true")
        val url=System.getenv("DATABASE_URL") ?: error("DATABASE_URL required")
        val user=System.getenv("DB_USERNAME") ?: error("DB_USERNAME required")
        val password=System.getenv("DB_PASSWORD") ?: error("DB_PASSWORD required")
        require(url.startsWith("jdbc:postgresql:"))
        // A fresh schema per run; no public/user upload table is reset or altered.
        val schema="vidoora_p4_test_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("vidoora_p4_test_[0-9a-f]{32}").matches(schema))
        fun connection(): Connection = DriverManager.getConnection(url,user,password).also { c ->
            c.createStatement().use { it.execute("SET search_path TO $schema") }
        }
        DriverManager.getConnection(url,user,password).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate()
            val owner=UUID.randomUUID()
            connection().use { c -> c.prepareStatement("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (?,'fixture','fixture@example.test','not-a-real-login','Fixture')").use { it.setObject(1,owner); it.executeUpdate() } }
            fun seed(): UUID {
                val video=UUID.randomUUID(); val job=UUID.randomUUID()
                connection().use { c ->
                    c.prepareStatement("INSERT INTO videos(id,uploader_id,title,publication_state,raw_object_key) VALUES (?,?,'Fixture','UPLOADED',?)").use { it.setObject(1,video); it.setObject(2,owner); it.setString(3,"raw/$video.mp4"); it.executeUpdate() }
                    c.prepareStatement("INSERT INTO upload_sessions(video_id,uploader_id,upload_id_provider,object_key,total_bytes,status,expires_at) VALUES (?,?,'fixture',?,3,'COMPLETED',NOW()+INTERVAL '1 day')").use { it.setObject(1,video); it.setObject(2,owner); it.setString(3,"raw/$video.mp4"); it.executeUpdate() }
                    c.prepareStatement("INSERT INTO processing_jobs(id,video_id) VALUES (?,?)").use { it.setObject(1,job); it.setObject(2,video); it.executeUpdate() }
                }
                return job
            }
            fun sql(text: String) = connection().use { c -> c.createStatement().use { it.executeUpdate(text) } }
            val repo=PostgresProcessingRepository(::connection)
            seed(); seed()
            val threads=Executors.newFixedThreadPool(4)
            val claims=try { threads.invokeAll(List(4) { Callable { repo.claim("fixture-private") } }).mapNotNull { it.get() } } finally { threads.shutdownNow() }
            assertEquals(2,claims.size); assertEquals(2,claims.map { it.jobId }.distinct().size)
            val first=claims.first()
            val output=ProcessingOutput(MediaInfo(640,360,12.0,true),listOf(Rendition(640,360,800000)),Path.of("."))
            assertTrue(repo.complete(first,output)); assertFalse(repo.complete(first,output))
            connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT publication_state,master_playlist_key,(SELECT count(*) FROM video_renditions WHERE video_id='${first.videoId}') AS renditions FROM videos WHERE id='${first.videoId}'").use { r -> assertTrue(r.next()); assertEquals("PROCESSED",r.getString(1)); assertEquals(first.prefix+"master.m3u8",r.getString(2)); assertEquals(1,r.getInt(3)) } } }
            val stale=claims.last()
            sql("UPDATE processing_jobs SET lease_until=NOW()-INTERVAL '1 second' WHERE id='${stale.jobId}'")
            assertFalse(repo.heartbeat(stale)); assertFalse(repo.complete(stale,output)); assertFalse(repo.fail(stale,false))
            assertNull(repo.claim("fixture-private")) // expired attempt is requeued with backoff
            sql("UPDATE processing_jobs SET next_attempt_at=NOW() WHERE id='${stale.jobId}'")
            val replacement=checkNotNull(repo.claim("fixture-private"))
            assertNotEquals(stale.token,replacement.token); assertFalse(repo.complete(stale,output))
            assertTrue(repo.fail(replacement,false)) // exercises PostgreSQL interval/backoff SQL
            sql("UPDATE processing_jobs SET next_attempt_at=NOW() WHERE id='${stale.jobId}'")
            val invalid=checkNotNull(repo.claim("fixture-private"))
            assertTrue(repo.fail(invalid,true)); assertNull(repo.claim("fixture-private"))
            connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT status FROM processing_jobs WHERE id='${stale.jobId}'").use { r -> assertTrue(r.next()); assertEquals("FAILED",r.getString(1)) } } }
            val cleanup=mutableListOf<String>()
            sql("UPDATE processing_attempts SET cleanup_after=NOW() WHERE state='CLEANUP'")
            while(repo.cleanupOne { _,prefix -> cleanup += prefix }) { }
            assertEquals(3,cleanup.size); assertFalse(cleanup.contains(first.prefix))
        } finally {
            // Identifier was generated and checked above; only this run's private schema is removed.
            DriverManager.getConnection(url,user,password).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
