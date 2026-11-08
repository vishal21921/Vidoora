package com.vidoora.backend

import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.auth.JwtConfig
import com.vidoora.backend.modules.deletion.*
import com.vidoora.backend.modules.playback.*
import com.vidoora.backend.modules.processing.*
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.net.URI
import java.sql.DriverManager
import java.util.UUID

class LiveVideoDeletionIntegrationTest {
    @Test fun tombstoneAndRetryableR2DeletionRemoveOnlySyntheticTargetAssets()=runBlocking {
        assumeTrue("Explicit isolated PostgreSQL/R2 fixture opt-in required",System.getenv("VIDOORA_LIVE_PROCESSING_TEST")=="true")
        fun env(name: String)=checkNotNull(System.getenv(name))
        val bucket=env("R2_TEST_BUCKET"); require(bucket.startsWith("vidoora-test-"))
        val url=env("DATABASE_URL"); require(url.startsWith("jdbc:postgresql:"))
        val username=env("DB_USERNAME"); val password=env("DB_PASSWORD")
        val schema="vidoora_delete_test_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("vidoora_delete_test_[0-9a-f]{32}").matches(schema))
        fun connection()=DriverManager.getConnection(url,username,password).also { c -> c.createStatement().use { it.execute("SET search_path TO $schema") } }
        val target=UUID.randomUUID(); val neighbor=UUID.randomUUID(); val owner=UUID.randomUUID(); val viewer=UUID.randomUUID()
        val attempt=UUID.randomUUID(); val abandoned=UUID.randomUUID(); val job=UUID.randomUUID(); val oldWorker=UUID.randomUUID()
        val raw="raw/$target.mp4"; val otherRaw="raw/$neighbor.mp4"
        val prefix="processed/$target/$attempt/"; val otherPrefix="processed/$neighbor/${UUID.randomUUID()}/"
        val ownedKeys=listOf(raw,otherRaw,prefix+"master.m3u8",prefix+"360p/index.m3u8",prefix+"360p/segment_000000.ts",prefix+"thumbnail.jpg",
            "processed/$target/$abandoned/orphan.ts",otherPrefix+"sentinel.ts")
        val config=MapApplicationConfig("r2.mockMode" to "false","r2.endpoint" to env("R2_ENDPOINT"),"r2.accessKeyId" to env("R2_ACCESS_KEY_ID"),
            "r2.secretAccessKey" to env("R2_SECRET_ACCESS_KEY"),"r2.rawBucketName" to bucket,"r2.processedBucketName" to bucket)
        val s3=S3Client.builder().region(Region.US_EAST_1).endpointOverride(URI.create(env("R2_ENDPOINT")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(env("R2_ACCESS_KEY_ID"),env("R2_SECRET_ACCESS_KEY")))).build()
        val previous=TransactionManager.defaultDatabase
        DriverManager.getConnection(url,username,password).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
            for(key in ownedKeys) s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),RequestBody.fromString("Synthetic deletion fixture"))
            connection().use { c ->
                for((id,name) in listOf(owner to "owner",viewer to "viewer")) c.prepareStatement("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (?,?,?,'not-a-login',?)").use {
                    it.setObject(1,id); it.setString(2,name); it.setString(3,"$name@example.test"); it.setString(4,name); it.executeUpdate()
                }
                for(id in listOf(target,neighbor)) c.prepareStatement("INSERT INTO videos(id,uploader_id,title,publication_state,visibility,is_published,published_at,raw_object_key,processed_bucket,master_playlist_key,thumbnail_worker_id,thumbnail_lease_until) VALUES (?,?,'Synthetic fixture','PROCESSED','UNLISTED',TRUE,NOW(),?,?,?, ?,NOW()+INTERVAL '2 minutes')").use {
                    it.setObject(1,id); it.setObject(2,owner); it.setString(3,"raw/$id.mp4"); it.setString(4,bucket)
                    it.setString(5,if(id==target) prefix+"master.m3u8" else otherPrefix+"master.m3u8"); it.setObject(6,oldWorker); it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO processing_jobs(id,video_id,status,worker_id,lease_until) VALUES (?,?,'PROCESSING',?,NOW()+INTERVAL '2 minutes')").use {
                    it.setObject(1,job); it.setObject(2,target); it.setString(3,oldWorker.toString()); it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO processing_attempts(id,job_id,bucket,object_prefix,state) VALUES (?,?,?,?,'COMMITTED')").use {
                    it.setObject(1,abandoned); it.setObject(2,job); it.setString(3,bucket); it.setString(4,"processed/$target/$abandoned/"); it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO upload_sessions(video_id,uploader_id,upload_id_provider,object_key,total_bytes,status,expires_at) VALUES (?,?,'fixture',?,10,'COMPLETED',NOW()+INTERVAL '1 day')").use {
                    it.setObject(1,target); it.setObject(2,owner); it.setString(3,raw); it.executeUpdate()
                }
            }
            val db=Database.connect(url+(if(url.contains('?')) "&" else "?")+"currentSchema=$schema",driver="org.postgresql.Driver",user=username,password=password)
            TransactionManager.defaultDatabase=previous
            S3StorageService(config).use { storage ->
                val service=PlaybackService(storage,db,VideoShareTokens(JwtConfig(MapApplicationConfig("jwt.secret" to "delete-fixture-secret-at-least-32-characters"))))
                val token=service.shareLink(owner.toString(),target.toString()).url.substringAfter('#')
                assertTrue(service.details(viewer.toString(),target.toString(),token).canPlay)
                try { service.deleteVideo(viewer.toString(),target.toString()); fail("Foreign deletion accepted") } catch (_: PlaybackDenied) { }
                service.deleteVideo(owner.toString(),target.toString())
                service.deleteVideo(owner.toString(),target.toString())
                try { service.details(viewer.toString(),target.toString(),token); fail("Deleted link still worked") } catch (_: PlaybackDenied) { }
                assertEquals(listOf(neighbor.toString()),service.libraryPage(owner.toString()).items.map { it.id })
                assertFalse(PostgresProcessingRepository(::connection).heartbeat(ProcessingClaim(job,target,oldWorker,raw,10,bucket,prefix)))
                assertFalse(ThumbnailRepository(::connection).heartbeat(ThumbnailClaim(target,oldWorker,bucket,prefix+"master.m3u8")))
                val repo=PostgresVideoDeletionRepository(bucket,::connection)
                assertNull(repo.claim()) // Five-minute safety delay applies even when Delete returns success.
                fun due() { connection().use { c -> c.createStatement().use { it.executeUpdate("UPDATE videos SET deletion_cleanup_next_at=NOW() WHERE id='$target'") } } }
                due(); val stale=checkNotNull(repo.claim())
                connection().use { c -> c.createStatement().use { it.executeUpdate("UPDATE videos SET deletion_cleanup_lease_until=NOW()-INTERVAL '1 second' WHERE id='$target'") } }
                val current=checkNotNull(repo.claim()); assertFalse(repo.complete(stale)); assertTrue(repo.heartbeat(current))
                repo.fail(current); assertNull(repo.claim()); due()
                val failing=object : S3StorageService(config) {
                    override fun deleteVideoObjects(videoId: UUID,rawKey: String?,buckets: List<String>,alive: ()->Boolean) { throw java.io.IOException("synthetic offline fixture") }
                }
                failing.use { VideoDeletionWorker(repo,it).runOne() }
                assertNull(repo.claim()); due()
                assertTrue(VideoDeletionWorker(repo,storage).runOne()); assertFalse(VideoDeletionWorker(repo,storage).runOne())
                for(key in ownedKeys.filter { it!=otherRaw && !it.startsWith(otherPrefix) }) {
                    try { s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()); fail("Target fixture asset remains") }
                    catch(e: S3Exception) { assertEquals(404,e.statusCode()) }
                }
                assertTrue(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(otherRaw).build()).contentLength()>0)
                assertTrue(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(otherPrefix+"sentinel.ts").build()).contentLength()>0)
                connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT storage_deleted_at,raw_object_key FROM videos WHERE id='$target'").use { r -> assertTrue(r.next()); assertNotNull(r.getTimestamp(1)); assertNull(r.getString(2)) } } }
            }
        } finally {
            TransactionManager.defaultDatabase=previous
            try { for(key in ownedKeys) s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()) }
            finally {
                s3.close()
                DriverManager.getConnection(url,username,password).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
            }
        }
    }
}
