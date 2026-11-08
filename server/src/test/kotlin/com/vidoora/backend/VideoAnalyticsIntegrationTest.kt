package com.vidoora.backend

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.analytics.*
import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.playback.*
import com.vidoora.backend.modules.user.UsersTable
import com.vidoora.backend.modules.upload.CompleteUploadRequest
import com.vidoora.backend.modules.upload.RefreshPartsRequest
import com.vidoora.backend.modules.upload.UploadService
import com.vidoora.backend.modules.membership.AdminViewerAccess
import com.vidoora.backend.modules.membership.RoleAwareViewerEntitlements
import com.vidoora.backend.modules.membership.FreeViewerEntitlements
import com.vidoora.backend.modules.membership.VerifiedGrantSource
import com.vidoora.backend.modules.downloads.PrivateDownloads
import com.vidoora.backend.modules.video.VideosTable
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import java.time.*
import java.util.UUID
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.routing
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import com.vidoora.backend.plugins.*

class VideoAnalyticsIntegrationTest {
    @Test fun realPostgresMetricsAreIdempotentScopedAndRevocable() = runBlocking {
        assumeTrue(System.getenv("VIDOORA_POSTGRES_TEST") == "true")
        val url = checkNotNull(System.getenv("DATABASE_URL"))
        val username = checkNotNull(System.getenv("DB_USERNAME"))
        val password = checkNotNull(System.getenv("DB_PASSWORD"))
        val schema = "vidoora_analytics_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, username, password).use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        val scoped = url + (if (url.contains('?')) "&" else "?") + "currentSchema=$schema"
        val config = MapApplicationConfig("database.jdbcUrl" to scoped, "database.username" to username,
            "database.password" to password, "jwt.secret" to "analytics-fixture-secret-at-least-32-characters", "r2.mockMode" to "true")
        DatabaseFactory.close()
        try {
            DatabaseFactory.init(config)
            val auth = AuthService(JwtConfig(config))
            val creator = auth.register(RegisterRequest(email="creator@example.test",password="Password123!"))
            val other = auth.register(RegisterRequest(email="other@example.test",password="Password123!"))
            val admin = auth.register(RegisterRequest(email="admin@example.test",password="Password123!"))
            DatabaseFactory.dbQuery {
                UsersTable.update({ UsersTable.id eq UUID.fromString(creator.user.id) }) { it[role] = "CREATOR" }
                UsersTable.update({ UsersTable.id eq UUID.fromString(other.user.id) }) { it[role] = "CREATOR" }
                UsersTable.update({ UsersTable.id eq UUID.fromString(admin.user.id) }) { it[role] = "ADMIN" }
            }
            val video = UUID.randomUUID()
            DatabaseFactory.dbQuery { VideosTable.insert {
                it[id]=video;it[uploaderId]=UUID.fromString(creator.user.id);it[title]="Analytics fixture";it[publicationState]="PROCESSED"
                it[durationSeconds]=60;it[processedBucket]="fixture";it[masterPlaylistKey]="processed/$video/${UUID.randomUUID()}/master.m3u8"
            } }
            val storage = object : S3StorageService(config) {
                override fun readProcessed(bucket: String, key: String, maxBytes: Int): ByteArray =
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\n360p/index.m3u8\n".toByteArray()
            }
            val playback = PlaybackService(storage,shareTokens=VideoShareTokens(JwtConfig(config)))
            playback.publish(creator.user.id, video.toString(), "UNLISTED")
            val token = playback.shareLink(creator.user.id, video.toString()).url.substringAfter('#')
            var now = Instant.now()
            val clock = object: Clock() { override fun getZone()=ZoneOffset.UTC; override fun withZone(zone: ZoneId)=this; override fun instant()=now }
            val analytics = VideoAnalytics(playback, JwtConfig(config).secret, clock)
            val health = analytics.operations.read(admin.user.id)
            assertEquals("UNKNOWN", health.processor)
            assertNull(health.lastVerifiedBackup)
            assertEquals(0L, health.queued)
            assertEquals(0L, health.stuck)
            assertTrue(runCatching { analytics.operations.read(other.user.id) }.exceptionOrNull() is PlaybackDenied)
            DatabaseFactory.jdbcTransaction { c ->
                c.prepareStatement("INSERT INTO service_operational_status(component,last_seen) VALUES ('processor',?)").use {
                    it.setTimestamp(1,java.sql.Timestamp.from(now)); it.executeUpdate()
                }
            }
            assertEquals("RECENT", analytics.operations.read(admin.user.id).processor)
            DatabaseFactory.jdbcTransaction { c ->
                c.prepareStatement("INSERT INTO service_operational_status(component,last_seen) VALUES ('database-backup',?)").use {
                    it.setTimestamp(1,java.sql.Timestamp.from(now.minusSeconds(300))); it.executeUpdate()
                }
            }
            assertEquals(now.minusSeconds(300).toEpochMilli(),Instant.parse(analytics.operations.read(admin.user.id).lastVerifiedBackup).toEpochMilli())
            now = now.plusSeconds(91)
            assertEquals("STALE", analytics.operations.read(admin.user.id).processor)
            now = now.minusSeconds(91)
            val adService=analytics.ads
            val adRequest=AdSdkEvent(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"INTERSTITIAL","REQUEST")
            val adToday=LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata"))).toString()
            adService.record(adRequest.copy(event="IMPRESSION")) // Missing request must not count.
            assertTrue(adService.report(admin.user.id,adToday,adToday).formats.isEmpty())
            adService.record(adRequest)
            adService.record(adRequest.copy(event="LOADED"))
            adService.record(adRequest.copy(event="IMPRESSION"))
            adService.record(adRequest.copy(event="IMPRESSION")) // Retry is idempotent.
            adService.record(adRequest.copy(deviceKey=UUID.randomUUID().toString(),event="CLICK")) // Wrong device.
            val adReport=adService.report(admin.user.id,adToday,adToday)
            assertEquals(1L,adReport.formats.single().impressions)
            assertEquals(0L,adReport.formats.single().clicks)
            assertEquals(1L,adReport.devices)
            assertTrue(runCatching {adService.report(other.user.id,adToday,adToday)}.exceptionOrNull() is PlaybackDenied)
            assertTrue(runCatching {adService.invite(other.user.id)}.exceptionOrNull() is PlaybackDenied)
            val generatedInvite=adService.invite(admin.user.id)
            assertEquals(now.plusSeconds(300),Instant.parse(generatedInvite.expiresAt))
            val invited=auth.register(RegisterRequest(email="invited@example.test",password="Password123!",creatorInviteCode=generatedInvite.code))
            assertEquals("CREATOR",invited.user.role)
            assertTrue(runCatching {auth.redeemCreatorInvite(other.user.id,generatedInvite.code)}.isFailure)
            val expired=adService.invite(admin.user.id)
            DatabaseFactory.jdbcTransaction { c ->
                c.prepareStatement("UPDATE creator_invites SET expires_at=NOW()-INTERVAL '1 second' WHERE redeemed_by IS NULL AND created_by=?").use {
                    it.setObject(1,UUID.fromString(admin.user.id));it.executeUpdate()
                }
            }
            assertTrue(runCatching {auth.redeemCreatorInvite(other.user.id,expired.code)}.isFailure)
            repeat(8) { adService.invite(admin.user.id) }
            assertTrue(runCatching {adService.invite(admin.user.id)}.isFailure)
            val adminAccess = checkNotNull(AdminViewerAccess.access(admin.user.id, now))
            assertTrue(adminAccess.adFree)
            assertEquals(Int.MAX_VALUE, adminAccess.plan.downloadsPerPeriod)
            assertNull(AdminViewerAccess.access(creator.user.id, now))
            val benefits = RoleAwareViewerEntitlements(FreeViewerEntitlements)
            assertEquals("ADMIN", benefits.resolve(admin.user.id).planId)
            val adminDownloads = PrivateDownloads(playback, VerifiedGrantSource { emptyList() }, clock, benefits)
            assertEquals(Int.MAX_VALUE, adminDownloads.quota(admin.user.id).limit)
            assertEquals(0, adminDownloads.quota(creator.user.id).limit)
            val paymentId = UUID.randomUUID()
            DatabaseFactory.jdbcTransaction { connection ->
                connection.prepareStatement("""
                    INSERT INTO purchase_attempts
                        (id,user_id,token_hash,encrypted_token,payment_state,activation_state,plan_id,expires_at,created_at,updated_at)
                    VALUES (?,?,'fixture-payment','fixture-encrypted','SUCCESS','ACTIVE','WEEK',?,?,?)
                """).use {
                    it.setObject(1, paymentId)
                    it.setObject(2, UUID.fromString(creator.user.id))
                    it.setObject(3, now.plusSeconds(86400).atOffset(ZoneOffset.UTC))
                    it.setObject(4, now.atOffset(ZoneOffset.UTC))
                    it.setObject(5, now.atOffset(ZoneOffset.UTC))
                    it.executeUpdate()
                }
            }
            val payments = analytics.payments(admin.user.id, 30, "SUCCESSFUL", creator.user.publicId, 0)
            assertEquals(1L, payments.week)
            assertEquals(paymentId.toString(), payments.items.single().reference)
            assertEquals(paymentId.toString(),analytics.payments(admin.user.id,1,"ALL",creator.user.publicId,0).items.single().reference)
            assertTrue(analytics.payments(admin.user.id,-2,"ALL",creator.user.publicId,0).items.isEmpty())
            DatabaseFactory.jdbcTransaction { c -> c.prepareStatement("UPDATE purchase_attempts SET created_at=? WHERE id=?").use {
                it.setObject(1,now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().minusDays(1).atTime(12,0).atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime())
                it.setObject(2,paymentId);it.executeUpdate()
            } }
            assertEquals(paymentId.toString(),analytics.payments(admin.user.id,-2,"ALL",creator.user.publicId,0).items.single().reference)
            assertTrue(analytics.payments(admin.user.id,1,"ALL",creator.user.publicId,0).items.isEmpty())
            DatabaseFactory.jdbcTransaction { c -> c.prepareStatement("UPDATE purchase_attempts SET created_at=? WHERE id=?").use {
                it.setObject(1,now.atOffset(ZoneOffset.UTC));it.setObject(2,paymentId);it.executeUpdate()
            } }
            val cycle=AdminReportingCycle.containing(now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate())
            val monthPayments=analytics.payments(admin.user.id,-1,"ALL",creator.user.publicId,0)
            assertEquals(paymentId.toString(),monthPayments.items.single().reference)
            assertEquals(cycle.start.toString(),checkNotNull(monthPayments.earnings).periodStart)
            assertNull(monthPayments.earnings?.todayMinor)
            // A transaction one second before the 15th belongs to the prior custom month.
            DatabaseFactory.jdbcTransaction { c -> c.prepareStatement("UPDATE purchase_attempts SET created_at=? WHERE id=?").use {
                it.setObject(1,cycle.start.atStartOfDay(ZoneId.of("Asia/Kolkata")).minusSeconds(1).toOffsetDateTime())
                it.setObject(2,paymentId);it.executeUpdate()
            } }
            assertTrue(analytics.payments(admin.user.id,-1,"ALL",creator.user.publicId,0).items.isEmpty())
            DatabaseFactory.jdbcTransaction { c -> c.prepareStatement("UPDATE purchase_attempts SET created_at=? WHERE id=?").use {
                it.setObject(1,now.atOffset(ZoneOffset.UTC));it.setObject(2,paymentId);it.executeUpdate()
            } }
            assertTrue(analytics.payments(admin.user.id, 30, "FAILED", "", 0).items.isEmpty())
            assertTrue(runCatching { analytics.payments(creator.user.id, 30, "ALL", "", 0) }.exceptionOrNull() is PlaybackDenied)
            val event = AnalyticsEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            analytics.event(null, video.toString(), token, event)
            analytics.event(null, video.toString(), token, event)
            analytics.event(creator.user.id, video.toString(), null, AnalyticsEvent(UUID.randomUUID().toString(),event.viewerKey))
            var total = analytics.report(creator.user.id,null,null,null,null).totals
            assertEquals(1L,total.opens);assertEquals(0L,total.starts)
            for (seconds in listOf(10L,20L,30L)) {
                now = now.plusSeconds(10)
                analytics.event(null, video.toString(), token, event.copy(playedMs=seconds*1000,started=true))
                analytics.event(null, video.toString(), token, event.copy(playedMs=seconds*1000,started=true))
            }
            total = analytics.report(creator.user.id,null,null,null,null).totals
            assertEquals(1L,total.views);assertEquals(1L,total.uniqueViewers);assertEquals(30L,total.watchSeconds)
            assertEquals(0L,analytics.report(other.user.id,null,null,null,null).totals.opens)
            val report = analytics.report(admin.user.id,null,null,creator.user.publicId,video.toString())
            assertTrue(report.admin);assertEquals(1,report.creators.size);assertEquals(1,report.videos.size)
            assertEquals(creator.user.publicId,report.creators.single().id)
            assertEquals(0L,analytics.report(other.user.id,null,null,creator.user.publicId,null).totals.opens)
            val unwatched = UUID.randomUUID()
            DatabaseFactory.dbQuery {
                VideosTable.insert {
                    it[id] = unwatched
                    it[uploaderId] = UUID.fromString(creator.user.id)
                    it[title] = "Unwatched fixture"
                }
            }
            val roster = analytics.uploaders(admin.user.id, null, null, "", "views", 0, false)
            assertEquals(4L, roster.total) // Includes the newly invited creator fixture.
            assertEquals(2L, roster.videoCount)
            assertEquals(creator.user.publicId, roster.items.first().id)
            assertTrue(roster.items.all { it.email == null })
            assertEquals(other.user.email, analytics.uploaders(admin.user.id, null, null,
                other.user.publicId, "name", 0, true).items.single().email)
            val catalog = analytics.catalogVideos(admin.user.id, creator.user.publicId, null, null, "", "views", 0)
            assertEquals(listOf(video.toString(), unwatched.toString()), catalog.items.map { it.id })
            assertEquals(0L, catalog.items.last().views)
            DatabaseFactory.jdbcTransaction { connection ->
                connection.prepareStatement("""
                    INSERT INTO processing_jobs (id,video_id,status,lease_until,progress_percent,created_at)
                    VALUES (?,?,'PROCESSING',now() - interval '1 minute',40,now() - interval '2 minutes')
                """).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, unwatched)
                    it.executeUpdate()
                }
            }
            val stuck = analytics.uploadHealth(admin.user.id, "STUCK", 0).items.single()
            assertEquals("Unwatched fixture", stuck.title)
            assertEquals(creator.user.publicId, stuck.uploaderId)
            assertEquals(40, stuck.progress)
            assertTrue(stuck.elapsedSeconds >= 120)
            assertTrue(analytics.uploadHealth(admin.user.id, "PROCESSING", 0).items.isEmpty())
            assertTrue(catalog.items.first().shared)
            assertEquals(60, catalog.items.first().durationSeconds)
            assertEquals(unwatched.toString(), analytics.catalogVideos(creator.user.id, null, null, null,
                "uNwAtChEd", "watch", 0).items.single().id)
            assertTrue(analytics.catalogVideos(other.user.id, null, null, null, "", "views", 0).items.isEmpty())
            val routeJwt = JwtConfig(config)
            testApplication {
                environment { this.config = MapApplicationConfig() }
                application {
                    configureSerialization()
                    configureSecurity(routeJwt)
                    configureStatusPages()
                    routing { analyticsRoutes(analytics) }
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/analytics/report").status)
                val creatorToken = routeJwt.generateAccessToken(other.user.id,"fixture@example.test","ADMIN")
                val scopedReport = client.get("/api/v1/analytics/report?creator=" + creator.user.publicId) { bearerAuth(creatorToken) }
                assertEquals(HttpStatusCode.OK, scopedReport.status)
                assertTrue(Regex("\"opens\"\\s*:\\s*0").containsMatchIn(scopedReport.bodyAsText()))
                for (path in listOf("uploaders", "uploaders/manage", "videos?creator=${creator.user.publicId}",
                    "videos/$video/link", "videos/$video/thumbnail")) {
                    assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/analytics/$path") {
                        bearerAuth(creatorToken)
                    }.status)
                }
                assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/analytics/uploaders/${creator.user.publicId}") {
                    bearerAuth(creatorToken)
                }.status)
                val adminToken = routeJwt.generateAccessToken(admin.user.id, admin.user.email, "ADMIN")
                assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/analytics/service-health").status)
                assertEquals(HttpStatusCode.Forbidden,client.get("/api/v1/analytics/service-health") {bearerAuth(creatorToken)}.status)
                val healthRead=client.get("/api/v1/analytics/service-health") {bearerAuth(adminToken)}
                assertEquals(HttpStatusCode.OK,healthRead.status)
                assertEquals("no-store",healthRead.headers[HttpHeaders.CacheControl])
                assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/analytics/ads").status)
                assertEquals(HttpStatusCode.Forbidden,client.get("/api/v1/analytics/ads") {bearerAuth(creatorToken)}.status)
                val adsRead=client.get("/api/v1/analytics/ads") {bearerAuth(adminToken)}
                assertEquals(HttpStatusCode.OK,adsRead.status)
                assertEquals("no-store",adsRead.headers[HttpHeaders.CacheControl])
                assertEquals(HttpStatusCode.Forbidden,client.post("/api/v1/analytics/creator-invites") {bearerAuth(creatorToken)}.status)
                assertEquals(HttpStatusCode.BadRequest,client.post("/api/v1/analytics/ads/event") {
                    contentType(ContentType.Application.Json);setBody("x".repeat(1025))
                }.status)
                assertEquals(HttpStatusCode.Accepted,client.post("/api/v1/analytics/ads/event") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"requestId":"${adRequest.requestId}","deviceKey":"${adRequest.deviceKey}","format":"INTERSTITIAL","event":"IMPRESSION"}""")
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/analytics/payments") {
                    bearerAuth(creatorToken)
                }.status)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/analytics/payments") {
                    bearerAuth(adminToken)
                }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/analytics/upload-health") {
                    bearerAuth(creatorToken)
                }.status)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/analytics/upload-health?status=STUCK") {
                    bearerAuth(adminToken)
                }.status)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/analytics/videos/$video/link") {
                    bearerAuth(adminToken)
                }.status)
                assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/analytics/uploaders/${admin.user.publicId}") {
                    bearerAuth(adminToken)
                }.status)
                assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/analytics/uploaders/${creator.user.publicId}") {
                    bearerAuth(adminToken)
                }.status)
                val page = client.get("/analytics")
                assertEquals(HttpStatusCode.OK, page.status)
                assertTrue(page.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
                assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/analytics/videos/$video/event") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"sessionId":"${event.sessionId}","viewerKey":"${event.viewerKey}"}""")
                }.status)
            }
            assertEquals("USER", auth.getUserProfile(creator.user.id).role)
            assertTrue(runCatching { analytics.report(creator.user.id,null,null,null,null) }.exceptionOrNull() is PlaybackDenied)
            val upload = UploadService(storage)
            val session = UUID.randomUUID().toString()
            assertEquals("A creator invitation is required to upload", runCatching {
                upload.refreshUploadPartUrls(creator.user.id, video.toString(), RefreshPartsRequest(session, listOf(1)))
            }.exceptionOrNull()?.message)
            assertEquals("A creator invitation is required to upload", runCatching {
                upload.completeUpload(creator.user.id, video.toString(), CompleteUploadRequest(session, emptyList()))
            }.exceptionOrNull()?.message)
            val removed = analytics.uploaders(admin.user.id, null, null, creator.user.publicId, "views", 0, false).items.single()
            assertFalse(removed.active)
            assertEquals(1L, removed.views)
            assertTrue(playback.details(null, video.toString(), token).canPlay)
            val invite = UUID.randomUUID().toString()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(invite.toByteArray())
                .joinToString("") { "%02x".format(it) }
            DatabaseFactory.dbQuery {
                CreatorInvites.insert { it[hash] = digest; it[expires] = Instant.now().plusSeconds(3600) }
            }
            assertEquals("CREATOR", auth.redeemCreatorInvite(creator.user.id, invite).role)
            assertEquals(1L, analytics.report(creator.user.id,null,null,null,null).totals.views)
            assertEquals(2L, analytics.catalogVideos(creator.user.id,null,null,null,"","views",0).total)
            // A fabricated large heartbeat is bounded to elapsed time and a single heartbeat budget.
            analytics.event(null, video.toString(), token, event.copy(playedMs=20000*1000,started=true,ended=true))
            assertTrue(analytics.report(admin.user.id,null,null,null,null).totals.watchSeconds <= 31)
            // A final cumulative update must recover all 80 watched seconds after a missed heartbeat.
            now = now.plusSeconds(50)
            analytics.event(null, video.toString(), token, event.copy(playedMs=80_000,started=true,ended=true))
            // The forged counter above is monotonic, so use a fresh independent viewing session.
            val fullWatch = AnalyticsEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            analytics.event(null, video.toString(), token, fullWatch)
            now = now.plusSeconds(80)
            analytics.event(null, video.toString(), token, fullWatch.copy(playedMs=80_000,started=true,ended=true))
            val fullReport = analytics.report(admin.user.id,null,null,null,video.toString()).totals
            assertEquals(2L, fullReport.views)
            assertTrue(fullReport.watchSeconds >= 110)
            val attacker = event.copy(viewerKey=UUID.randomUUID().toString())
            assertTrue(runCatching { analytics.event(null,video.toString(),token,attacker) }.isFailure)
            playback.unpublish(creator.user.id,video.toString())
            assertTrue(runCatching { analytics.event(null,video.toString(),token,event) }.isFailure)
            DatabaseFactory.dbQuery { UsersTable.update({ UsersTable.id eq UUID.fromString(admin.user.id) }) { it[role]="USER" } }
            assertNull(AdminViewerAccess.access(admin.user.id, now))
            assertEquals("FREE", benefits.resolve(admin.user.id).planId)
            assertEquals(0, adminDownloads.quota(admin.user.id).limit)
            assertTrue(runCatching { analytics.report(admin.user.id,null,null,null,null) }.isFailure)
            assertTrue(runCatching { analytics.operations.read(admin.user.id) }.exceptionOrNull() is PlaybackDenied)
            assertTrue(runCatching { adService.report(admin.user.id,adToday,adToday) }.exceptionOrNull() is PlaybackDenied)
            assertTrue(runCatching { adService.invite(admin.user.id) }.exceptionOrNull() is PlaybackDenied)
        } finally {
            DatabaseFactory.close()
            DriverManager.getConnection(url, username, password).use { it.createStatement().use { s -> s.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}


