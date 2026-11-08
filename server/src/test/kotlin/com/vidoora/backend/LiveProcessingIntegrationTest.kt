package com.vidoora.backend

import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.processing.*
import io.ktor.server.config.MapApplicationConfig
import org.flywaydb.core.Flyway
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.net.URI
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

class LiveProcessingIntegrationTest {
    @Test fun realPostgresR2AndFfmpegPipelineProducesDecodablePrivateHls() {
        assumeTrue("Explicit isolated live pipeline opt-in required",System.getenv("VIDOORA_LIVE_PROCESSING_TEST")=="true")
        fun env(name: String)=System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name required")
        val bucket=env("R2_TEST_BUCKET")
        require(bucket.startsWith("vidoora-test-"))
        val url=env("DATABASE_URL"); require(url.startsWith("jdbc:postgresql:"))
        val user=env("DB_USERNAME"); val password=env("DB_PASSWORD")
        val schema="vidoora_p4_test_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("vidoora_p4_test_[0-9a-f]{32}").matches(schema))
        fun connection()=DriverManager.getConnection(url,user,password).also { it.createStatement().use { s -> s.execute("SET search_path TO $schema") } }
        val work=Files.createTempDirectory("vidoora-attempt-live-test-")
        val rawKey="raw/phase4-verification-${UUID.randomUUID()}.mp4"
        var processedPrefix: String?=null
        var schemaCreated=false
        val config=MapApplicationConfig("r2.mockMode" to "false","r2.endpoint" to env("R2_ENDPOINT"),
            "r2.accessKeyId" to env("R2_ACCESS_KEY_ID"),"r2.secretAccessKey" to env("R2_SECRET_ACCESS_KEY"),
            "r2.rawBucketName" to bucket,"r2.processedBucketName" to bucket)
        val ffmpeg=env("FFMPEG_PATH"); val ffprobe=env("FFPROBE_PATH")
        val s3=S3Client.builder().region(Region.US_EAST_1).endpointOverride(URI.create(env("R2_ENDPOINT")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(env("R2_ACCESS_KEY_ID"),env("R2_SECRET_ACCESS_KEY")))).build()
        fun exec(args: List<String>) {
            val p=ProcessBuilder(args).redirectErrorStream(true).redirectOutput(work.resolve("tool.log").toFile()).start()
            try { assertTrue(p.waitFor(60,TimeUnit.SECONDS)); assertEquals(0,p.exitValue()) }
            finally { if(p.isAlive) p.destroyForcibly() }
        }
        try {
            DriverManager.getConnection(url,user,password).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }; schemaCreated=true
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
            val source=work.resolve("fixture.mp4")
            exec(listOf(ffmpeg,"-v","error","-y","-f","lavfi","-i","testsrc2=size=640x360:rate=30",
                "-f","lavfi","-i","sine=frequency=440:sample_rate=48000","-t","7","-c:v","libx264","-threads","2","-pix_fmt","yuv420p","-c:a","aac",source.toString()))
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(rawKey).contentType("video/mp4").build(),RequestBody.fromFile(source))
            val owner=UUID.randomUUID(); val video=UUID.randomUUID(); val job=UUID.randomUUID()
            connection().use { c ->
                c.prepareStatement("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (?,'fixture','fixture@example.test','not-a-real-login','Fixture')").use { it.setObject(1,owner); it.executeUpdate() }
                c.prepareStatement("INSERT INTO videos(id,uploader_id,title,visibility,publication_state,raw_object_key) VALUES (?,?,'Live processing fixture','PRIVATE','UPLOADED',?)").use { it.setObject(1,video); it.setObject(2,owner); it.setString(3,rawKey); it.executeUpdate() }
                c.prepareStatement("INSERT INTO upload_sessions(video_id,uploader_id,upload_id_provider,object_key,total_bytes,status,expires_at) VALUES (?,?,'fixture',?,?,'COMPLETED',NOW()+INTERVAL '1 day')").use { it.setObject(1,video); it.setObject(2,owner); it.setString(3,rawKey); it.setLong(4,Files.size(source)); it.executeUpdate() }
                c.prepareStatement("INSERT INTO processing_jobs(id,video_id) VALUES (?,?)").use { it.setObject(1,job); it.setObject(2,video); it.executeUpdate() }
            }
            S3StorageService(config).use { store ->
                val root=work.resolve("scratch"); Files.createDirectories(root)
                val repo=PostgresProcessingRepository(::connection)
                val result=ProcessingWorker(repo,store,root,FfmpegTranscoder(ffmpeg,ffprobe)::transcode).runOne()
                assertEquals(ProcessingRunResult.COMPLETED,result)
                val masterKey=connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT v.publication_state,v.master_playlist_key,v.master_playlist_url,j.status FROM videos v JOIN processing_jobs j ON j.video_id=v.id WHERE v.id='$video'").use { r ->
                    assertTrue(r.next()); assertEquals("PROCESSED",r.getString(1)); assertNull(r.getString(3)); assertEquals("COMPLETED",r.getString(4)); r.getString(2)
                } } }
                processedPrefix=masterKey.removeSuffix("master.m3u8")
                val master=s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(masterKey).build()).asUtf8String()
                assertTrue(master.contains("360p/index.m3u8")); assertFalse(master.contains("https:"))
                // Exercise private playback reads against this same isolated real PostgreSQL schema and R2 output.
                val previousDb=org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase
                val playbackDb=org.jetbrains.exposed.sql.Database.connect(url+(if(url.contains('?')) "&" else "?")+"currentSchema=$schema",
                    driver="org.postgresql.Driver",user=user,password=password)
                org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase=previousDb
                val shareTokens=com.vidoora.backend.modules.playback.VideoShareTokens(com.vidoora.backend.modules.auth.JwtConfig(MapApplicationConfig("jwt.secret" to "isolated-live-fixture-secret-over-32-characters")))
                val playback=com.vidoora.backend.modules.playback.PlaybackService(store,playbackDb,shareTokens)
                kotlinx.coroutines.runBlocking {
                    assertEquals(video.toString(),playback.library(owner.toString()).single().id)
                    assertArrayEquals(master.toByteArray(),playback.asset(owner.toString(),video.toString(),"master.m3u8"))
                    val jpeg=playback.thumbnail(owner.toString(),video.toString())
                    val preview=javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(jpeg))
                    assertEquals(480,preview.width); assertEquals(270,preview.height)
                    assertEquals("image/jpeg",s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(processedPrefix+"thumbnail.jpg").build()).contentType())
                    // Simulate an older processed video, prove lease fencing/retry, then backfill from HLS.
                    connection().use { c -> c.createStatement().use { it.executeUpdate("UPDATE videos SET thumbnail_object_key=NULL WHERE id='$video'") } }
                    val thumbs=ThumbnailRepository(::connection)
                    val stale=checkNotNull(thumbs.claim())
                    connection().use { c -> c.createStatement().use { it.executeUpdate("UPDATE videos SET thumbnail_lease_until=NOW()-INTERVAL '1 second' WHERE id='$video'") } }
                    val replacement=checkNotNull(thumbs.claim())
                    assertFalse(thumbs.complete(stale)); assertTrue(thumbs.heartbeat(replacement))
                    thumbs.fail(replacement); assertNull(thumbs.claim())
                    connection().use { c -> c.createStatement().use { it.executeUpdate("UPDATE videos SET thumbnail_retry_count=0,thumbnail_next_attempt_at=NOW() WHERE id='$video'") } }
                    assertEquals(ProcessingRunResult.COMPLETED,ThumbnailWorker(thumbs,store,root,FfmpegTranscoder(ffmpeg,ffprobe)::thumbnail).runOne())
                    assertNotNull(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(playback.thumbnail(owner.toString(),video.toString()))))
                    val viewer=UUID.randomUUID()
                    connection().use { c -> c.prepareStatement("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (?,'viewer','viewer@example.test','not-a-real-login','Viewer')").use { it.setObject(1,viewer); it.executeUpdate() } }
                    try { playback.thumbnail(viewer.toString(),video.toString()); fail("Viewer read owner preview") } catch (_: com.vidoora.backend.modules.playback.PlaybackDenied) { }
                    try { playback.publish(owner.toString(),video.toString(),"PUBLIC"); fail("Public publication accepted") }
                    catch (_: IllegalArgumentException) { }
                    assertTrue(playback.discover(viewer.toString(),null).items.isEmpty())
                    try { playback.asset(viewer.toString(),video.toString(),"master.m3u8"); fail("Video ID alone granted access") }
                    catch (_: com.vidoora.backend.modules.playback.PlaybackDenied) { }
                    playback.publish(owner.toString(),video.toString(),"UNLISTED")
                    assertTrue(playback.discover(viewer.toString(),null).items.isEmpty())
                    val oldCapability=playback.shareLink(owner.toString(),video.toString()).url.substringAfter('#')
                    assertArrayEquals(master.toByteArray(),playback.asset(viewer.toString(),video.toString(),"master.m3u8",oldCapability))
                    // Real PostgreSQL block persistence and private R2 authorization.
                    connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM reports").use { r -> assertTrue(r.next()); assertEquals(0,r.getInt(1)) } } }
                    playback.blockUploader(viewer.toString(),video.toString(),oldCapability)
                    assertEquals(owner.toString(),playback.blockedUploaders(viewer.toString(),null).items.single().id)
                    for(asset in listOf("master.m3u8","360p/index.m3u8","360p/segment_000000.ts")) {
                        try { playback.asset(viewer.toString(),video.toString(),asset,oldCapability); fail("Blocked uploader remained accessible") }
                        catch (_: com.vidoora.backend.modules.playback.PlaybackDenied) { }
                    }
                    playback.unblockUploader(viewer.toString(),owner.toString())
                    assertArrayEquals(master.toByteArray(),playback.asset(viewer.toString(),video.toString(),"master.m3u8",oldCapability))
                    playback.unpublish(owner.toString(),video.toString()); playback.publish(owner.toString(),video.toString(),"UNLISTED")
                    try { playback.asset(viewer.toString(),video.toString(),"master.m3u8",oldCapability); fail("Revoked share link allowed") }
                    catch (_: com.vidoora.backend.modules.playback.PlaybackDenied) { }
                    val renewed=playback.shareLink(owner.toString(),video.toString()).url.substringAfter('#')
                    assertArrayEquals(master.toByteArray(),playback.asset(viewer.toString(),video.toString(),"master.m3u8",renewed))
                    try { playback.asset(UUID.randomUUID().toString(),video.toString(),"master.m3u8"); fail("Foreign owner allowed") }
                    catch (_: com.vidoora.backend.modules.playback.PlaybackDenied) { }
                }
                val download=work.resolve("download"); Files.createDirectories(download.resolve("360p"))
                Files.writeString(download.resolve("master.m3u8"),master)
                val playlistKey=processedPrefix+"360p/index.m3u8"
                val playlist=s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(playlistKey).build()).asUtf8String()
                assertTrue(playlist.contains("#EXT-X-ENDLIST"))
                Files.writeString(download.resolve("360p/index.m3u8"),playlist)
                for(line in playlist.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }) {
                    require(Regex("segment_[0-9]{6}\\.ts").matches(line))
                    s3.getObject(GetObjectRequest.builder().bucket(bucket).key(processedPrefix+"360p/"+line).build(),ResponseTransformer.toFile(download.resolve("360p").resolve(line)))
                }
                exec(listOf(ffmpeg,"-v","error","-i",download.resolve("360p/index.m3u8").toString(),"-f","null","-"))
                Files.list(root).use { assertEquals(0L,it.count()) }
            }
        } finally {
            // Discover all this schema's attempt prefixes even when processing fails before commit.
            try {
                if(schemaCreated) connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT object_prefix FROM processing_attempts").use { r ->
                    S3StorageService(config).use { store -> while(r.next()) store.deleteProcessingPrefix(bucket,r.getString(1)) }
                } } }
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(rawKey).build())
            } finally {
                s3.close()
                if(schemaCreated) DriverManager.getConnection(url,user,password).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
                ProcessingWorker.removeAttemptDirectory(work.parent,work)
            }
        }
    }
}





