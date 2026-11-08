package com.vidoora.backend

import com.vidoora.backend.modules.membership.membershipRoutes
import com.vidoora.backend.infrastructure.*
import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.playback.*
import com.vidoora.backend.modules.user.UsersTable
import com.vidoora.backend.modules.video.VideosTable
import com.vidoora.backend.modules.video.UploadSessionsTable
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.routing
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import com.vidoora.backend.plugins.*

class PlaybackSecurityTest {
    private val config=MapApplicationConfig("jwt.secret" to "test-only-secret-at-least-32-characters-long",
        "database.jdbcUrl" to "jdbc:h2:mem:vidoora_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL","database.driverClassName" to "org.h2.Driver",
        "database.username" to "sa","database.password" to "","database.allowInMemoryFallback" to "true","r2.mockMode" to "true")
    private lateinit var owner: String
    private lateinit var other: String
    private lateinit var store: Store
    private lateinit var service: PlaybackService
    private class Store(config: MapApplicationConfig) : S3StorageService(config) {
        var calls=0
        var image: ByteArray?=null
        var writes=0
        override fun uploadThumbnail(bucket: String,key: String,bytes: ByteArray) { writes++;image=bytes;assertTrue(key.endsWith("/custom-thumbnail.jpg")) }
        var text="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\n360p/index.m3u8\n"
        override fun readProcessed(bucket: String,key: String,maxBytes: Int): ByteArray { calls++; return image ?: text.toByteArray() }
    }
    @Before fun setup() = runBlocking {
        DatabaseFactory.init(config)
        val auth=AuthService(JwtConfig(config))
        suspend fun register(): String { val suffix=UUID.randomUUID().toString().take(8); return auth.register(RegisterRequest("p_$suffix","$suffix@example.test","Password123!","Fixture")).user.id }
        owner=register(); other=register(); store=Store(config); service=PlaybackService(store,shareTokens=VideoShareTokens(JwtConfig(config)))
    }
    private suspend fun video(state: String="PROCESSED",visibility: String="PRIVATE"): String {
        val id=UUID.randomUUID()
        DatabaseFactory.dbQuery { VideosTable.insert {
            it[VideosTable.id]=id; it[uploaderId]=UUID.fromString(owner); it[title]="Playback fixture"; it[publicationState]=state; it[VideosTable.visibility]=visibility
            it[processedBucket]="vidoora-test-playback"; it[masterPlaylistKey]="processed/$id/${UUID.randomUUID()}/master.m3u8"
        } }
        return id.toString()
    }
    private suspend fun denied(block: suspend ()->Unit) {
        try { block(); fail("Access should fail") } catch (_: PlaybackDenied) { }
    }
    @Test fun edgeAuthorizationReusesPrivacyQualityAndRevocationWithoutStorageReads()=runBlocking {
        val id=video()
        denied { service.authorizeAsset(other,id,"360p/segment_000000.ts") }
        val grant=service.authorizeAsset(owner,id,"360p/segment_000000.ts")
        assertEquals("vidoora-test-playback",grant.bucket)
        assertTrue(grant.key.startsWith("processed/$id/"))
        assertEquals(0,store.calls)
        try { service.authorizeAsset(owner,id,"720p/segment_000000.ts");fail() } catch(_: PlaybackQualityRestricted) { }
        service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        val before=store.calls
        service.authorizeAsset(null,id,"360p/segment_000000.ts",token)
        service.blockUploader(other,id,token)
        denied { service.authorizeAsset(other,id,"360p/segment_000000.ts",token) }
        service.unpublish(owner,id)
        denied { service.authorizeAsset(null,id,"360p/segment_000000.ts",token) }
        assertEquals(before,store.calls)
    }
    @Test fun edgeEndpointRequiresSeparateSecretAndNeverReturnsManifests()=testApplication {
        environment { config=MapApplicationConfig() }
        val id=video();val jwt=JwtConfig(config);val secret="fixture-edge-secret-32-characters-long"
        application {
            configureSerialization();configureSecurity(jwt);configureStatusPages()
            routing { mediaEdgeRoutes(service,MediaEdgeConfig(true,secret)) }
        }
        suspend fun attempt(edge: String?,asset: String="360p/segment_000000.ts",account: String=owner)=client.post("/api/v1/internal/media/authorize") {
            bearerAuth(jwt.generateAccessToken(account,"fixture@example.test","USER"))
            edge?.let { header("X-Vidoora-Edge",it) };contentType(ContentType.Application.Json)
            setBody("""{"videoId":"$id","asset":"$asset"}""")
        }
        assertEquals(HttpStatusCode.NotFound,attempt(null).status)
        assertEquals(HttpStatusCode.NotFound,attempt("wrong").status)
        assertEquals(HttpStatusCode.NotFound,attempt(secret,"master.m3u8").status)
        assertEquals(HttpStatusCode.NotFound,attempt(secret,account=other).status)
        assertEquals(HttpStatusCode.Forbidden,attempt(secret,"720p/segment_000000.ts").status)
        val result=attempt(secret)
        assertEquals(HttpStatusCode.OK,result.status);assertEquals("no-store",result.headers[HttpHeaders.CacheControl])
        assertTrue(result.bodyAsText().contains("processed/$id/"));assertEquals(0,store.calls)
        assertFalse(MediaEdgeConfig().accepts(secret))
    }
    @Test fun libraryDoesNotExposeOtherAccountsOrStorageKeys()=runBlocking {
        val id=video(); assertTrue(service.library(owner).any { it.id==id }); assertFalse(service.library(other).any { it.id==id })
    }
    @Test fun otherAccountCannotReadEvenPublicUnpublishedVideo()=runBlocking {
        for(visibility in listOf("PUBLIC","PRIVATE","UNLISTED")) { val id=video(visibility=visibility); denied { service.asset(other,id,"master.m3u8") } }
        assertEquals(0,store.calls)
    }
    @Test fun ownerCanReadProcessedManifestButNotRawOrTraversedKeys()=runBlocking {
        val id=video(); assertTrue(service.asset(owner,id,"master.m3u8").isNotEmpty())
        for(path in listOf("../raw/private.mp4","360p/../../master.m3u8","https://example.test/video","raw/test.mp4","360p/segment_000001.ts?token=x","master.m3u8/extra")) denied { service.asset(owner,id,path) }
        assertEquals(1,store.calls)
    }
    @Test fun unprocessedAndSuspendedOwnersCannotPlay()=runBlocking {
        val id=video("UPLOADED")
        try { service.asset(owner,id,"master.m3u8"); fail() } catch (_: PlaybackUnavailable) { }
        val ready=video()
        DatabaseFactory.dbQuery { UsersTable.update({ UsersTable.id eq UUID.fromString(owner) }) { it[isSuspended]=true } }
        denied { service.asset(owner,ready,"master.m3u8") }; denied { service.library(owner) }
        assertEquals(0,store.calls)
    }
    @Test fun externalAndEmbeddedManifestUrisAreRejected()=runBlocking {
        val id=video()
        for(text in listOf("#EXTM3U\nhttps://example.test/stolen.ts\n","#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.test/key\"\n360p/index.m3u8\n")) {
            store.text=text
            try { service.asset(owner,id,"master.m3u8"); fail("Unsafe manifest accepted") } catch (_: PlaybackUnavailable) { }
        }
    }
    @Test fun generatedVariantPlaylistIsAccepted() {
        PlaybackPaths.validateManifest("360p/index.m3u8","#EXTM3U\n#EXTINF:6.0,\nsegment_000000.ts\n#EXT-X-ENDLIST\n")
        assertTrue(PlaybackPaths.validAsset("720p/segment_000012.ts"))
    }
    @Test fun routesPreservePrivateOwnerChecksWithOptionalLogin() = testApplication {
        environment { config=MapApplicationConfig() }
        val id=video()
        val jwt=JwtConfig(config)
        application {
            configureSerialization(); configureSecurity(jwt); configureStatusPages()
            routing { playbackRoutes(service) }
        }
        val path="/api/v1/videos/$id/playback/master.m3u8"
        assertEquals(HttpStatusCode.NotFound,client.get(path).status)
        assertEquals(HttpStatusCode.Unauthorized,client.get(path) { header(HttpHeaders.Authorization,"Bearer "+jwt.generateRefreshToken(owner)) }.status)
        assertEquals(HttpStatusCode.NotFound,client.get(path) { header(HttpHeaders.Authorization,"Bearer "+jwt.generateAccessToken(other,"fixture@example.test","USER")) }.status)
        val result=client.get(path) { header(HttpHeaders.Authorization,"Bearer "+jwt.generateAccessToken(owner,"fixture@example.test","USER")) }
        assertEquals(HttpStatusCode.OK,result.status)
        assertEquals("no-store",result.headers[HttpHeaders.CacheControl])
        assertTrue(result.headers[HttpHeaders.ContentType]!!.startsWith("application/vnd.apple.mpegurl"))
    }
    @Test fun publicPublicationAndLegacyIdOnlyAccessAreDisabled()=runBlocking {
        val id=video(visibility="PUBLIC")
        DatabaseFactory.dbQuery { VideosTable.update({ VideosTable.id eq UUID.fromString(id) }) { it[isPublished]=true; it[publishedAt]=java.time.Instant.now() } }
        assertTrue(service.discover(other,null).items.isEmpty())
        denied { service.asset(other,id,"master.m3u8") }; denied { service.details(other,id) }
        denied { service.shareLink(owner,id) }
        try { service.publish(owner,id,"PUBLIC"); fail("Public publishing accepted") } catch (_: IllegalArgumentException) { }
        service.unpublish(owner,id)
        assertTrue(service.asset(owner,id,"master.m3u8").isNotEmpty())
    }
    @Test fun unlistedLinksAreScopedAndRevokedAcrossPublicationChanges()=runBlocking {
        val first=video(); val second=video()
        service.publish(owner,first,"UNLISTED"); service.publish(owner,second,"UNLISTED")
        val token=service.shareLink(owner,first).url.substringAfter('#')
        assertFalse(service.discover(other,null).items.any { it.id==first })
        denied { service.asset(other,first,"master.m3u8") }
        denied { service.asset(other,second,"master.m3u8",token) }
        assertTrue(service.asset(other,first,"master.m3u8",token).isNotEmpty())
        service.publish(owner,first,"UNLISTED") // Idempotent publish must not break an existing link.
        assertTrue(service.asset(other,first,"master.m3u8",token).isNotEmpty())
        service.unpublish(owner,first); denied { service.asset(other,first,"master.m3u8",token) }
        service.publish(owner,first,"UNLISTED"); denied { service.asset(other,first,"master.m3u8",token) }
        val replacement=service.shareLink(owner,first).url.substringAfter('#')
        assertTrue(service.asset(other,first,"master.m3u8",replacement).isNotEmpty())
    }
    @Test fun expiredAndAccessTokensCannotActAsShareCapabilities()=runBlocking {
        val video=video(); service.publish(owner,video,"UNLISTED")
        val version=DatabaseFactory.dbQuery { VideosTable.select { VideosTable.id eq UUID.fromString(video) }.single()[VideosTable.shareVersion] }
        val expired=VideoShareTokens(JwtConfig(config)).issue(UUID.fromString(video),version,java.time.Instant.now().minusSeconds(5))
        denied { service.asset(other,video,"master.m3u8",expired) }
        denied { service.thumbnail(null,video,expired) }
        denied { service.asset(other,video,"master.m3u8",JwtConfig(config).generateAccessToken(other,"fixture@example.test","USER")) }
    }
    @Test fun publicationAndShareCreationRequireOwnerAndProcessedMedia()=runBlocking {
        val ready=video(); val pending=video("UPLOADED")
        denied { service.publish(other,ready,"UNLISTED") }; denied { service.unpublish(other,ready) }; denied { service.shareLink(other,ready) }
        try { service.publish(owner,pending,"UNLISTED"); fail() } catch (_: PlaybackUnavailable) { }
        try { service.publish(owner,ready,"PRIVATE"); fail() } catch (_: IllegalArgumentException) { }
        try { service.shareLink(owner,ready); fail() } catch (_: PlaybackUnavailable) { }
        store.text="invalid manifest"
        try { service.publish(owner,ready,"UNLISTED"); fail() } catch (_: PlaybackUnavailable) { }
        assertFalse(service.details(owner,ready).published)
    }
    @Test fun uploaderSuspensionWithdrawsEvenValidLinkAccess()=runBlocking {
        val id=video(); service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        assertTrue(service.asset(other,id,"master.m3u8",token).isNotEmpty())
        DatabaseFactory.dbQuery { UsersTable.update({ UsersTable.id eq UUID.fromString(owner) }) { it[isSuspended]=true } }
        denied { service.asset(other,id,"master.m3u8",token) }
    }
    @Test fun retiredDiscoveryNeverDisclosesAnyRecords()=runBlocking {
        repeat(3) { val id=video(); service.publish(owner,id,"UNLISTED") }
        val legacy=video(visibility="PUBLIC")
        DatabaseFactory.dbQuery { VideosTable.update({ VideosTable.id eq UUID.fromString(legacy) }) { it[isPublished]=true; it[publishedAt]=java.time.Instant.now() } }
        for(limit in listOf(1,20,50)) {
            val page=service.discover(other,"old-client-cursor",limit)
            assertTrue(page.items.isEmpty()); assertNull(page.nextCursor)
        }
        try { service.discover(other,null,51); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun shareJwtIsRejectedAsApiAuthentication()=testApplication {
        environment { config=MapApplicationConfig() }
        val id=video(); service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        val jwt=JwtConfig(config)
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { playbackRoutes(service) } }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/videos/mine") { header(HttpHeaders.Authorization,"Bearer $token") }.status)
        val access=jwt.generateAccessToken(other,"fixture@example.test","USER")
        val path="/api/v1/videos/$id/playback/master.m3u8"
        assertEquals(HttpStatusCode.NotFound,client.get(path) { header(HttpHeaders.Authorization,"Bearer $access") }.status)
        assertEquals(HttpStatusCode.OK,client.get(path) { header(HttpHeaders.Authorization,"Bearer $access"); header("X-Vidoora-Share",token) }.status)
        assertEquals(HttpStatusCode.NotFound,client.post("/api/v1/videos/$id/unpublish") { header(HttpHeaders.Authorization,"Bearer $access") }.status)
    }
    @Test fun blockingDeniesEveryAssetAndAllVideosFromThatUploaderOnly()=runBlocking {
        val first=video(); val second=video()
        service.publish(owner,first,"UNLISTED"); service.publish(owner,second,"UNLISTED")
        val firstToken=service.shareLink(owner,first).url.substringAfter('#')
        val secondToken=service.shareLink(owner,second).url.substringAfter('#')
        denied { service.blockUploader(other,first,null) }; denied { service.blockUploader(owner,first,null) }
        service.blockUploader(other,first,firstToken); service.blockUploader(other,first,firstToken)
        val calls=store.calls
        for(path in listOf("master.m3u8","360p/index.m3u8","360p/segment_000000.ts")) denied { service.asset(other,first,path,firstToken) }
        denied { service.details(other,first,firstToken) }; denied { service.asset(other,second,"master.m3u8",secondToken) }
        assertEquals(calls,store.calls)
        assertTrue(service.details(owner,first).canPlay); assertEquals(2,service.library(owner).count { it.id==first || it.id==second })
        assertEquals(listOf(owner),service.blockedUploaders(other,null).items.map { it.id })
        assertTrue(service.blockedUploaders(owner,null).items.isEmpty())
        // An unblock by another account cannot remove the viewer's preference.
        service.unblockUploader(owner,owner)
        denied { service.asset(other,first,"master.m3u8",firstToken) }
        service.unblockUploader(other,owner); service.unblockUploader(other,owner)
        assertTrue(service.blockedUploaders(other,null).items.isEmpty())
        denied { service.asset(other,first,"master.m3u8") }
        assertTrue(service.asset(other,first,"master.m3u8",firstToken).isNotEmpty())
    }
    @Test fun blockedListPaginationAndAccountIsolation()=runBlocking {
        DatabaseFactory.dbQuery {
            val ids=(1..52).map { UUID.randomUUID() }.sorted()
            for(target in ids) {
                UsersTable.insert { it[id]=target; it[username]="b_"+target.toString().take(24); it[email]="$target@example.test"; it[passwordHash]="fixture"; it[displayName]="Blocked fixture" }
                UserBlocksTable.insert { it[blockerId]=UUID.fromString(other); it[blockedId]=target }
            }
        }
        val first=service.blockedUploaders(other,null)
        assertEquals(50,first.items.size); assertNotNull(first.nextCursor)
        val last=service.blockedUploaders(other,first.nextCursor)
        assertEquals(2,last.items.size); assertNull(last.nextCursor)
        assertEquals(52,(first.items+last.items).map { it.id }.distinct().size)
        assertTrue(service.blockedUploaders(owner,null).items.isEmpty())
    }
    @Test fun blockRoutesAuthenticateAndReportingIsUnavailable()=testApplication {
        environment { config=MapApplicationConfig() }
        val id=video(); service.publish(owner,id,"UNLISTED")
        val jwt=JwtConfig(config); val viewerJwt=jwt.generateAccessToken(other,"fixture@example.test","USER")
        val capability=service.shareLink(owner,id).url.substringAfter('#')
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { playbackRoutes(service) } }
        // Older clients cannot submit report data, even with valid login and sharing access.
        assertEquals(HttpStatusCode.NotFound,client.post("/api/v1/videos/$id/report") {
            bearerAuth(viewerJwt); header("X-Vidoora-Share",capability); contentType(ContentType.Application.Json); setBody("""{"reason":"SPAM"}""")
        }.status)
        DatabaseFactory.dbQuery { assertEquals(0L,ReportsTable.select { ReportsTable.videoId eq UUID.fromString(id) }.count()) }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/users/me/blocked-uploaders").status)
        assertEquals(HttpStatusCode.OK,client.post("/api/v1/videos/$id/block-uploader") { bearerAuth(viewerJwt); header("X-Vidoora-Share",capability) }.status)
        assertEquals(HttpStatusCode.NotFound,client.get("/api/v1/videos/$id/details") { bearerAuth(viewerJwt); header("X-Vidoora-Share",capability) }.status)
        assertEquals(HttpStatusCode.OK,client.delete("/api/v1/users/me/blocked-uploaders/$owner") { bearerAuth(viewerJwt) }.status)
    }
    @Test fun ownerLibraryKeysetReachesOlderUploadsWithDateTies()=runBlocking {
        val ids=(1..63).map { UUID.randomUUID() }
        DatabaseFactory.dbQuery { ids.forEachIndexed { index,uuid -> VideosTable.insert {
            it[id]=uuid; it[uploaderId]=UUID.fromString(owner); it[title]="Library $index"
            it[createdAt]=java.time.Instant.parse("2026-10-01T00:00:00Z").minusSeconds((index/7).toLong())
        } } }
        val found=mutableListOf<String>(); var cursor: String?=null
        do {
            val page=service.libraryPage(owner,cursor=cursor,limit=10)
            assertTrue(page.items.size<=10); found.addAll(page.items.map { it.id }); cursor=page.nextCursor
        } while(cursor!=null)
        assertEquals(ids.size,found.size); assertEquals(ids.map { it.toString() }.toSet(),found.toSet())
        assertTrue(service.libraryPage(other).items.isEmpty())
    }
    @Test fun ownerSearchUsesLiteralCaseInsensitiveTitleOrDescription()=runBlocking {
        val one=video(); val two=video(); val three=video()
        DatabaseFactory.dbQuery {
            VideosTable.update({ VideosTable.id eq UUID.fromString(one) }) { it[title]="100%_! Holiday"; it[description]="Family NEEDLE memories" }
            VideosTable.update({ VideosTable.id eq UUID.fromString(two) }) { it[title]="100XX Holiday" }
            VideosTable.update({ VideosTable.id eq UUID.fromString(three) }) { it[title]="Needle in title" }
        }
        assertEquals(setOf(one,three),service.libraryPage(owner,"  needle  ").items.map { it.id }.toSet())
        assertEquals(listOf(one),service.libraryPage(owner,"%_!").items.map { it.id })
        assertEquals(3,service.libraryPage(owner," ").items.size)
        assertTrue(service.libraryPage(other,"needle").items.isEmpty())
        for(block in listOf<suspend ()->Unit>(
            { service.libraryPage(owner,"x".repeat(101)) },{ service.libraryPage(owner,limit=0) },
            { service.libraryPage(owner,limit=51) },{ service.libraryPage(owner,cursor="broken") },
            { service.libraryPage(owner,cursor="a".repeat(257)) })) {
            try { block(); fail("Invalid library request accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun metadataEditingIsOwnerOnlyAndPreservesSharingAndStorage()=runBlocking {
        val id=video(); service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        val before=DatabaseFactory.dbQuery { VideosTable.select { VideosTable.id eq UUID.fromString(id) }.single() }
        denied { service.editDetails(other,id,EditVideoRequest("Foreign edit")) }
        val updated=service.editDetails(owner,id,EditVideoRequest("  New title  ","  New description  "))
        assertEquals("New title",updated.title); assertEquals("New description",updated.description)
        val after=DatabaseFactory.dbQuery { VideosTable.select { VideosTable.id eq UUID.fromString(id) }.single() }
        assertEquals(before[VideosTable.shareVersion],after[VideosTable.shareVersion])
        assertEquals(before[VideosTable.visibility],after[VideosTable.visibility]); assertTrue(after[VideosTable.isPublished])
        assertEquals(before[VideosTable.publishedAt],after[VideosTable.publishedAt])
        assertEquals(before[VideosTable.masterPlaylistKey],after[VideosTable.masterPlaylistKey])
        assertEquals(before[VideosTable.processedBucket],after[VideosTable.processedBucket])
        assertEquals(before[VideosTable.publicationState],after[VideosTable.publicationState])
        assertEquals("New title",service.details(other,id,token).title)
        assertTrue(service.asset(other,id,"master.m3u8",token).isNotEmpty())
        for(request in listOf(EditVideoRequest(" "),EditVideoRequest("x".repeat(129)),EditVideoRequest("Okay","x".repeat(5001)))) {
            try { service.editDetails(owner,id,request); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals("New title",service.details(owner,id).title)
        assertNull(service.editDetails(owner,id,EditVideoRequest("New title","  ")).description)
    }
    @Test fun libraryAndEditingRoutesRejectForeignAccountsAndInvalidParameters()=testApplication {
        environment { config=MapApplicationConfig() }
        val id=video(); val jwt=JwtConfig(config)
        val ownerJwt=jwt.generateAccessToken(owner,"fixture@example.test","USER")
        val otherJwt=jwt.generateAccessToken(other,"fixture@example.test","USER")
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { playbackRoutes(service) } }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/videos/mine/page").status)
        assertEquals(HttpStatusCode.OK,client.get("/api/v1/videos/mine/page?q=Playback") { bearerAuth(ownerJwt) }.status)
        assertEquals(HttpStatusCode.BadRequest,client.get("/api/v1/videos/mine/page?cursor=broken") { bearerAuth(ownerJwt) }.status)
        assertEquals(HttpStatusCode.BadRequest,client.get("/api/v1/videos/mine/page?limit=no") { bearerAuth(ownerJwt) }.status)
        assertEquals(HttpStatusCode.Unauthorized,client.patch("/api/v1/videos/$id/details") { contentType(ContentType.Application.Json); setBody("""{"title":"Edited"}""") }.status)
        assertEquals(HttpStatusCode.NotFound,client.patch("/api/v1/videos/$id/details") { bearerAuth(otherJwt); contentType(ContentType.Application.Json); setBody("""{"title":"Edited"}""") }.status)
        assertEquals(HttpStatusCode.OK,client.patch("/api/v1/videos/$id/details") { bearerAuth(ownerJwt); contentType(ContentType.Application.Json); setBody("""{"title":"Edited","description":"Description"}""") }.status)
    }    @Test fun thumbnailsRequireOwnerOrActiveVideoScopedShareCapability()=runBlocking {
        val id=video(); service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        val key=DatabaseFactory.dbQuery { VideosTable.select { VideosTable.id eq UUID.fromString(id) }.single()[VideosTable.masterPlaylistKey]!!.removeSuffix("master.m3u8")+"thumbnail.jpg" }
        DatabaseFactory.dbQuery { VideosTable.update({ VideosTable.id eq UUID.fromString(id) }) { it[thumbnailObjectKey]=key } }
        store.image=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
        val before=store.calls
        denied { service.thumbnail(other,id) }; assertEquals(before,store.calls)
        assertArrayEquals(store.image,service.thumbnail(owner,id))
        assertTrue(service.libraryPage(owner).items.single { it.id==id }.thumbnailAvailable)
        assertArrayEquals(store.image,service.thumbnail(null,id,token))
        assertArrayEquals(store.image,service.thumbnail(other,id,token))
        val another=video(); denied { service.thumbnail(null,another,token) }
        service.blockUploader(other,id,token); denied { service.thumbnail(other,id,token) }
        service.unpublish(owner,id); denied { service.thumbnail(null,id,token) }
        assertTrue(service.details(owner,id).canPlay)
        denied { service.asset(other,id,"thumbnail.jpg",token) }
    }
    private fun jpeg(width: Int=480,height: Int=270): ByteArray = java.io.ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpeg",it)
    }.toByteArray()
    @Test fun customThumbnailIsOwnerOnlyBoundedAndPreservesSharing()=runBlocking {
        val id=video();service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        denied { service.changeThumbnail(other,id,jpeg()) };assertEquals(0,store.writes)
        val changed=service.changeThumbnail(owner,id,jpeg())
        assertTrue(changed.published);assertEquals("UNLISTED",changed.visibility);assertNotNull(changed.thumbnailVersion)
        assertEquals(1,store.writes);assertArrayEquals(store.image,service.thumbnail(null,id,token))
        service.changeThumbnail(owner,id,jpeg(320,180));assertEquals(2,store.writes)
        service.deleteVideo(owner,id);denied { service.thumbnail(null,id,token) }
        denied { service.changeThumbnail(owner,id,jpeg()) };assertEquals(2,store.writes)
    }
    @Test fun invalidAndOversizedImageUploadsNeverReachStorage()=runBlocking {
        val id=video()
        for(bytes in listOf(byteArrayOf(1,2,3,4),byteArrayOf(-1,-40,0,0),ByteArray(256*1024+1),jpeg(481,270),jpeg(480,271))) {
            try { service.changeThumbnail(owner,id,bytes);fail("Invalid image accepted") } catch(_: IllegalArgumentException) { }
        }
        assertEquals(0,store.writes)
        val waiting=video("UPLOADED")
        try { service.changeThumbnail(owner,waiting,jpeg());fail() } catch(_: PlaybackUnavailable) { }
    }
    @Test fun thumbnailHttpReadAcceptsShareButEditAlwaysRequiresOwner()=testApplication {
        environment { config=MapApplicationConfig() }
        val id=video();service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#');val jwt=JwtConfig(config)
        service.changeThumbnail(owner,id,jpeg())
        application { configureSerialization();configureSecurity(jwt);configureStatusPages();routing { playbackRoutes(service) } }
        val path="/api/v1/videos/$id/thumbnail"
        val read=client.get(path) { header("X-Vidoora-Share",token) }
        assertEquals(HttpStatusCode.OK,read.status);assertEquals("no-store",read.headers[HttpHeaders.CacheControl]);assertEquals("nosniff",read.headers["X-Content-Type-Options"])
        assertEquals(HttpStatusCode.Unauthorized,client.put(path) { header("X-Vidoora-Share",token);contentType(ContentType.Image.JPEG);setBody(jpeg()) }.status)
        assertEquals(HttpStatusCode.NotFound,client.put(path) { bearerAuth(jwt.generateAccessToken(other,"fixture@example.test","USER"));contentType(ContentType.Image.JPEG);setBody(jpeg()) }.status)
        assertEquals(HttpStatusCode.OK,client.put(path) { bearerAuth(jwt.generateAccessToken(owner,"fixture@example.test","USER"));contentType(ContentType.Image.JPEG);setBody(jpeg()) }.status)
        assertEquals(HttpStatusCode.BadRequest,client.put(path) { bearerAuth(jwt.generateAccessToken(owner,"fixture@example.test","USER"));contentType(ContentType.Image.JPEG);setBody(ByteArray(256*1024+1)) }.status)
        assertEquals(HttpStatusCode.BadRequest,client.put(path) { bearerAuth(jwt.generateAccessToken(owner,"fixture@example.test","USER"));contentType(ContentType.Image.PNG);setBody(jpeg()) }.status)
        service.unpublish(owner,id)
        assertEquals(HttpStatusCode.NotFound,client.get(path) { header("X-Vidoora-Share",token) }.status)
    }
    @Test fun missingUnsafeAndOversizedThumbnailsFailWithoutExposingStorage()=runBlocking {
        val id=video()
        try { service.thumbnail(owner,id); fail() } catch (_: PlaybackUnavailable) { }
        DatabaseFactory.dbQuery { VideosTable.update({ VideosTable.id eq UUID.fromString(id) }) { it[thumbnailObjectKey]="processed/other/thumbnail.jpg" } }
        try { service.thumbnail(owner,id); fail() } catch (_: PlaybackUnavailable) { }
        assertEquals(0,store.calls)
        DatabaseFactory.dbQuery { VideosTable.update({ VideosTable.id eq UUID.fromString(id) }) {
            val master=VideosTable.select { VideosTable.id eq UUID.fromString(id) }.single()[VideosTable.masterPlaylistKey]!!
            it[thumbnailObjectKey]=master.removeSuffix("master.m3u8")+"thumbnail.jpg"
        } }
        store.image=ByteArray(256*1024+1)
        try { service.thumbnail(owner,id); fail() } catch (_: PlaybackUnavailable) { }
    }
    @Test fun thumbnailRouteRequiresAccessAndReturnsPrivateJpeg()=testApplication {
        environment { config=MapApplicationConfig() }
        val id=video(); val jwt=JwtConfig(config)
        DatabaseFactory.dbQuery { val row=VideosTable.select { VideosTable.id eq UUID.fromString(id) }.single()
            VideosTable.update({ VideosTable.id eq UUID.fromString(id) }) { it[thumbnailObjectKey]=row[VideosTable.masterPlaylistKey]!!.removeSuffix("master.m3u8")+"thumbnail.jpg" }
        }
        store.image=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { playbackRoutes(service) } }
        val path="/api/v1/videos/$id/thumbnail"
        assertEquals(HttpStatusCode.NotFound,client.get(path).status)
        assertEquals(HttpStatusCode.NotFound,client.get(path) { bearerAuth(jwt.generateAccessToken(other,"fixture@example.test","USER")) }.status)
        val result=client.get(path) { bearerAuth(jwt.generateAccessToken(owner,"fixture@example.test","USER")) }
        assertEquals(HttpStatusCode.OK,result.status); assertEquals("no-store",result.headers[HttpHeaders.CacheControl])
        assertTrue(result.headers[HttpHeaders.ContentType]!!.startsWith("image/jpeg")); assertEquals("nosniff",result.headers["X-Content-Type-Options"])
    }    @Test fun deletionIsOwnerOnlyIdempotentAndRevokesEveryAccessPath()=runBlocking {
        val target=video(); val neighbor=video(); service.publish(owner,target,"UNLISTED")
        val token=service.shareLink(owner,target).url.substringAfter('#')
        denied { service.deleteVideo(other,target) }
        assertTrue(service.details(other,target,token).canPlay)
        assertTrue(service.deleteVideo(owner,target).accepted); assertTrue(service.deleteVideo(owner,target).accepted)
        assertFalse(service.library(owner).any { it.id==target }); assertEquals(listOf(neighbor),service.libraryPage(owner).items.map { it.id })
        denied { service.details(owner,target) }; denied { service.details(other,target,token) }
        denied { service.asset(owner,target,"master.m3u8") }; denied { service.asset(other,target,"360p/segment_000000.ts",token) }
        denied { service.thumbnail(owner,target) }; denied { service.editDetails(owner,target,EditVideoRequest("Restore")) }
        denied { service.unpublish(owner,target) }; denied { service.publish(owner,target,"UNLISTED") }; denied { service.shareLink(owner,target) }
        DatabaseFactory.dbQuery { val row=VideosTable.select { VideosTable.id eq UUID.fromString(target) }.single()
            assertNotNull(row[VideosTable.deletedAt]); assertNull(row[VideosTable.storageDeletedAt]); assertFalse(row[VideosTable.isPublished])
            assertEquals("DELETED",row[VideosTable.publicationState]); assertEquals("Deleted video",row[VideosTable.title]); assertNull(row[VideosTable.description])
        }
    }
    @Test fun activeUploadMustBeCancelledBeforeDeletion()=runBlocking {
        val target=video("UPLOADING")
        DatabaseFactory.dbQuery { UploadSessionsTable.insert {
            it[id]=UUID.randomUUID(); it[videoId]=UUID.fromString(target); it[uploaderId]=UUID.fromString(owner)
            it[uploadIdProvider]="fixture"; it[objectKey]="raw/$target.mp4"; it[totalBytes]=10; it[expiresAt]=java.time.Instant.now().plusSeconds(600)
        } }
        try { service.deleteVideo(owner,target); fail("Active upload deleted") } catch (_: DeletionBusy) { }
        assertNotNull(service.details(owner,target))
        DatabaseFactory.dbQuery { UploadSessionsTable.update({ UploadSessionsTable.videoId eq UUID.fromString(target) }) { it[status]="ABORTED" } }
        assertTrue(service.deleteVideo(owner,target).accepted)
    }
    @Test fun deletionRouteNeedsLoginAndActualOwner()=testApplication {
        environment { config=MapApplicationConfig() }
        val target=video(); val jwt=JwtConfig(config)
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { playbackRoutes(service) } }
        val path="/api/v1/videos/$target"
        assertEquals(HttpStatusCode.Unauthorized,client.delete(path).status)
        assertEquals(HttpStatusCode.NotFound,client.delete(path) { bearerAuth(jwt.generateAccessToken(other,"fixture@example.test","USER")) }.status)
        val result=client.delete(path) { bearerAuth(jwt.generateAccessToken(owner,"fixture@example.test","USER")) }
        assertEquals(HttpStatusCode.OK,result.status); assertEquals("no-store",result.headers[HttpHeaders.CacheControl])
    }    @Test fun completedUploadRetryCannotRecreateDeletedVideoOrJob()=runBlocking {
        val target=video("UPLOADED"); val session=UUID.randomUUID()
        DatabaseFactory.dbQuery { UploadSessionsTable.insert {
            it[id]=session; it[videoId]=UUID.fromString(target); it[uploaderId]=UUID.fromString(owner)
            it[uploadIdProvider]="closed-fixture"; it[objectKey]="raw/$target.mp4"; it[totalBytes]=10
            it[status]="COMPLETED"; it[expiresAt]=java.time.Instant.now().plusSeconds(600)
        } }
        service.deleteVideo(owner,target)
        try {
            com.vidoora.backend.modules.upload.UploadService(store).completeUpload(owner,target,
                com.vidoora.backend.modules.upload.CompleteUploadRequest(session.toString(),emptyList()))
            fail("Completed upload replay resurrected a deleted video")
        } catch(e: IllegalStateException) { assertEquals("Video was deleted",e.message) }
        DatabaseFactory.dbQuery {
            assertEquals(0L,com.vidoora.backend.modules.video.ProcessingJobsTable.select { com.vidoora.backend.modules.video.ProcessingJobsTable.videoId eq UUID.fromString(target) }.count())
            assertEquals("DELETED",VideosTable.select { VideosTable.id eq UUID.fromString(target) }.single()[VideosTable.publicationState])
        }
    }    @Test fun bothSortDirectionsPageThroughTiesAndRejectOtherViewCursors()=runBlocking {
        val ids=(0..22).map { UUID.randomUUID() }
        DatabaseFactory.dbQuery { ids.forEachIndexed { i,uuid -> VideosTable.insert {
            it[id]=uuid; it[uploaderId]=UUID.fromString(owner); it[title]="Sort fixture"
            it[createdAt]=java.time.Instant.parse("2026-10-01T00:00:00Z").plusSeconds((i/4).toLong())
        } } }
        suspend fun collect(sort: String): List<String> {
            val result=mutableListOf<String>(); var cursor: String?=null
            do { val page=service.libraryPage(owner,cursor=cursor,limit=5,sort=sort); result.addAll(page.items.map { it.id }); cursor=page.nextCursor } while(cursor!=null)
            return result
        }
        val newest=collect("NEWEST"); val oldest=collect("OLDEST")
        assertEquals(23,newest.distinct().size); assertEquals(newest.reversed(),oldest)
        val cursor=service.libraryPage(owner,limit=5).nextCursor!!
        for(request in listOf<suspend ()->Unit>(
            { service.libraryPage(owner,cursor=cursor,sort="OLDEST") },{ service.libraryPage(owner,"different",cursor=cursor) },
            { service.libraryPage(owner,cursor=cursor,state="READY") },{ service.libraryPage(owner,cursor=cursor,sharing="PRIVATE") },
            { service.libraryPage(other,cursor=cursor) },{ service.libraryPage(owner,sort="SQL") },
            { service.libraryPage(owner,state="DELETED") },{ service.libraryPage(owner,sharing="PUBLIC") })) {
            try { request(); fail("Invalid/mismatched view accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun statusAndSharingFiltersCombineWithoutExposingDeletedOrForeignRows()=runBlocking {
        val private=video(); val shared=video(); val stopped=video(visibility="UNLISTED")
        val waiting=video("UPLOADED"); val processing=video("PROCESSING"); val failed=video("PROCESSING_FAILED")
        video("CANCELLED"); val deleted=video()
        DatabaseFactory.dbQuery {
            VideosTable.update({ VideosTable.id eq UUID.fromString(shared) }) { it[visibility]="UNLISTED"; it[isPublished]=true; it[publishedAt]=java.time.Instant.now() }
            VideosTable.update({ VideosTable.id eq UUID.fromString(deleted) }) { it[deletedAt]=java.time.Instant.now() }
        }
        assertEquals(setOf(private,shared,stopped),service.libraryPage(owner,state="READY").items.map { it.id }.toSet())
        assertEquals(setOf(waiting,processing),service.libraryPage(owner,state="PROCESSING").items.map { it.id }.toSet())
        assertEquals(listOf(failed),service.libraryPage(owner,state="FAILED").items.map { it.id })
        assertEquals(listOf(shared),service.libraryPage(owner,sharing="SHARED").items.map { it.id })
        assertEquals(setOf(private,stopped),service.libraryPage(owner,state="READY",sharing="PRIVATE").items.map { it.id }.toSet())
        assertTrue(service.libraryPage(owner,state="FAILED",sharing="SHARED").items.isEmpty())
        assertTrue(service.libraryPage(other,state="READY",sharing="SHARED").items.isEmpty())
        assertFalse(service.libraryPage(owner).items.any { it.id==deleted })
    }    @Test fun privateFoldersMoveRenameAndRemoveWithoutChangingSharing()=runBlocking {
        val first=video(); val second=video(); service.publish(owner,first,"UNLISTED")
        val token=service.shareLink(owner,first).url.substringAfter('#')
        val folder=service.saveFolder(owner,null,FolderNameRequest("  Family  "))
        assertEquals("Family",folder.name); assertTrue(service.folders(other).isEmpty())
        assertEquals(folder.id,service.moveToFolder(owner,first,folder.id).folderId)
        service.moveToFolder(owner,second,folder.id)
        assertNull(service.details(other,first,token).folderId)
        assertEquals(2,service.libraryPage(owner,folder=folder.id).items.size)
        assertTrue(service.libraryPage(owner,folder="UNFILED").items.isEmpty())
        val cursor=service.libraryPage(owner,folder=folder.id,limit=1).nextCursor!!
        try { service.libraryPage(owner,cursor=cursor); fail("Folder cursor used in All videos") } catch (_: IllegalArgumentException) { }
        assertEquals("Renamed",service.saveFolder(owner,folder.id,FolderNameRequest("Renamed")).name)
        service.removeFolder(owner,folder.id)
        assertNull(service.details(owner,first).folderId); assertEquals(2,service.libraryPage(owner,folder="UNFILED").items.size)
        assertTrue(service.details(other,first,token).canPlay)
        assertTrue(service.folders(owner).isEmpty())
    }
    @Test fun folderOwnershipAndNameValidationAreEnforced()=runBlocking {
        val video=video(); val folder=service.saveFolder(owner,null,FolderNameRequest("Work"))
        val foreign=service.saveFolder(other,null,FolderNameRequest("Work"))
        denied { service.moveToFolder(owner,video,foreign.id) }; denied { service.moveToFolder(other,video,foreign.id) }
        denied { service.saveFolder(other,folder.id,FolderNameRequest("Stolen")) }; denied { service.removeFolder(other,folder.id) }
        denied { service.libraryPage(other,folder=folder.id) }
        for(name in listOf("work"," ","x".repeat(65))) {
            try { service.saveFolder(owner,null,FolderNameRequest(name)); fail("Invalid/duplicate folder accepted") } catch (_: IllegalArgumentException) { }
        }
        service.deleteVideo(owner,video)
        denied { service.moveToFolder(owner,video,folder.id) }
        assertEquals("Work",service.folders(owner).single().name)
    }
    @Test fun folderRoutesRequireAuthenticationAndUseOwnerContext()=testApplication {
        environment { config=MapApplicationConfig() }; val jwt=JwtConfig(config); val video=video()
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { playbackRoutes(service) } }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/folders").status)
        assertEquals(HttpStatusCode.OK,client.post("/api/v1/folders") { bearerAuth(jwt.generateAccessToken(owner,"fixture@example.test","USER")); contentType(ContentType.Application.Json); setBody("""{"name":"Route fixture"}""") }.status)
        val folder=service.folders(owner).single()
        assertEquals(HttpStatusCode.NotFound,client.put("/api/v1/videos/$video/folder") { bearerAuth(jwt.generateAccessToken(other,"fixture@example.test","USER")); contentType(ContentType.Application.Json); setBody("""{"folderId":"${folder.id}"}""") }.status)
    }    @Test fun guestRequiresLiveVideoSpecificCapabilityForEveryAsset()=runBlocking {
        val id=video(); val another=video()
        denied { service.details(null,id) }; denied { service.asset(null,id,"master.m3u8") }
        service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        val detail=service.details(null,id,token)
        assertFalse(detail.isOwner); assertTrue(detail.canPlay); assertNull(detail.folderId)
        assertTrue(service.asset(null,id,"master.m3u8",token).isNotEmpty())
        store.text="#EXTM3U\n#EXTINF:6.0,\nsegment_000000.ts\n#EXT-X-ENDLIST\n"
        assertTrue(service.asset(null,id,"360p/index.m3u8",token).isNotEmpty())
        assertTrue(service.asset(null,id,"360p/segment_000000.ts",token).isNotEmpty())
        denied { service.asset(null,id,"360p/segment_000000.ts") }
        denied { service.details(null,another,token) }
        denied { service.asset(null,id,"../raw/video.mp4",token) }
        denied { service.details(null,id,"invalid.token.value") }
        val version=DatabaseFactory.dbQuery { VideosTable.select { VideosTable.id eq UUID.fromString(id) }.single()[VideosTable.shareVersion] }
        val expired=VideoShareTokens(JwtConfig(config)).issue(UUID.fromString(id),version,java.time.Instant.now().minusSeconds(10))
        denied { service.details(null,id,expired) }
        denied { service.details(null,id,JwtConfig(config).generateAccessToken(owner,"fixture@example.test","USER")) }
        service.unpublish(owner,id)
        denied { service.details(null,id,token) }; denied { service.asset(null,id,"360p/segment_000000.ts",token) }
    }
    @Test fun guestCannotWatchDeletedOrSuspendedCreators()=runBlocking {
        val id=video(); service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        service.deleteVideo(owner,id)
        denied { service.details(null,id,token) }; denied { service.asset(null,id,"master.m3u8",token) }
        val second=video(); service.publish(owner,second,"UNLISTED")
        val cap=service.shareLink(owner,second).url.substringAfter('#')
        DatabaseFactory.dbQuery { UsersTable.update({ UsersTable.id eq UUID.fromString(owner) }) { it[isSuspended]=true } }
        denied { service.details(null,second,cap) }
    }
    @Test fun guestHttpAccessNeverGrantsCreatorOrAccountActions() = testApplication {
        environment { config=MapApplicationConfig() }
        val id=video(); service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        val jwt=JwtConfig(config)
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { playbackRoutes(service) } }
        val base="/api/v1/videos/$id"
        assertEquals(HttpStatusCode.NotFound,client.get("$base/details").status)
        assertEquals(HttpStatusCode.NotFound,client.get("$base/details") { header("X-Vidoora-Share","bad.token.value") }.status)
        val details=client.get("$base/details") { header("X-Vidoora-Share",token) }
        assertEquals(HttpStatusCode.OK,details.status); assertEquals("no-store",details.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.OK,client.get("$base/playback/master.m3u8") { header("X-Vidoora-Share",token) }.status)
        for(path in listOf("/api/v1/videos/mine/page","/api/v1/folders","/api/v1/users/me/blocked-uploaders")) {
            assertEquals(HttpStatusCode.Unauthorized,client.get(path) { header("X-Vidoora-Share",token) }.status)
        }
        for(action in listOf("publish","unpublish","share-link","block-uploader")) {
            assertEquals(HttpStatusCode.Unauthorized,client.post("$base/$action") { header("X-Vidoora-Share",token) }.status)
        }
        assertEquals(HttpStatusCode.Unauthorized,client.delete(base) { header("X-Vidoora-Share",token) }.status)
        assertEquals(HttpStatusCode.Unauthorized,client.patch("$base/details") { header("X-Vidoora-Share",token) }.status)
        assertEquals(HttpStatusCode.Unauthorized,client.put("$base/folder") { header("X-Vidoora-Share",token) }.status)
        // Invalid identity cannot silently downgrade to guest; signed-in block preferences still apply.
        assertEquals(HttpStatusCode.Unauthorized,client.get("$base/details") { bearerAuth("invalid"); header("X-Vidoora-Share",token) }.status)
        service.blockUploader(other,id,token)
        assertEquals(HttpStatusCode.NotFound,client.get("$base/details") {
            bearerAuth(jwt.generateAccessToken(other,"fixture@example.test","USER")); header("X-Vidoora-Share",token)
        }.status)
        service.unpublish(owner,id)
        assertEquals(HttpStatusCode.NotFound,client.get("$base/playback/master.m3u8") { header("X-Vidoora-Share",token) }.status)
    }    @Test fun ownerStatusSnapshotsAreBoundedPrivateAndUseTheSameViewFilters()=runBlocking {
        val waiting=video("UPLOADED"); val ready=video(); val deleted=video()
        DatabaseFactory.dbQuery { VideosTable.update({ VideosTable.id eq UUID.fromString(deleted) }) { it[deletedAt]=java.time.Instant.now() } }
        val ids=listOf(waiting,ready,deleted,UUID.randomUUID().toString())
        assertEquals(setOf(waiting,ready),service.libraryStatuses(owner,OwnerVideoStatusRequest(ids)).map { it.id }.toSet())
        assertTrue(service.libraryStatuses(other,OwnerVideoStatusRequest(ids)).isEmpty())
        assertEquals(listOf(waiting),service.libraryStatuses(owner,OwnerVideoStatusRequest(ids,state="PROCESSING",search="fixture",sharing="PRIVATE")).map { it.id })
        assertEquals(listOf(ready),service.libraryStatuses(owner,OwnerVideoStatusRequest(ids,state="READY")).map { it.id })
        assertTrue(service.libraryStatuses(owner,OwnerVideoStatusRequest(ids,search="no match")).isEmpty())
        for(request in listOf(OwnerVideoStatusRequest(emptyList()),OwnerVideoStatusRequest(List(51) { waiting }),OwnerVideoStatusRequest(ids,state="bogus"))) {
            try { service.libraryStatuses(owner,request); fail("Invalid status request accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0,store.calls)
    }
    @Test fun statusHttpRoutesRequireCreatorAuthenticationAndNeverExposeForeignRows()=testApplication {
        environment { config=MapApplicationConfig() }
        val id=video("UPLOADED"); val jwt=JwtConfig(config)
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { playbackRoutes(service) } }
        val path="/api/v1/videos/mine/status"
        val body="""{"ids":["$id"]}"""
        assertEquals(HttpStatusCode.Unauthorized,client.post(path) { contentType(ContentType.Application.Json); setBody(body) }.status)
        val own=client.post(path) { bearerAuth(jwt.generateAccessToken(owner,"fixture@example.test","USER")); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.OK,own.status); assertEquals("no-store",own.headers[HttpHeaders.CacheControl])
        val foreign=client.post(path) { bearerAuth(jwt.generateAccessToken(other,"fixture@example.test","USER")); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.OK,foreign.status)
        assertFalse(foreign.bodyAsText().contains(id))
        assertEquals(HttpStatusCode.BadRequest,client.post(path) { bearerAuth(jwt.generateAccessToken(owner,"fixture@example.test","USER")); contentType(ContentType.Application.Json); setBody("""{"ids":[]}""") }.status)
    }

    @Test fun membershipQualityChecksFollowPrivacyAndBlockHighStorageReads()=runBlocking {
        val id=video()
        store.text="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000\n360p/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000\n720p/index.m3u8\n"
        var lookups=0
        val now=java.time.Instant.now()
        val membership=com.vidoora.backend.modules.membership.VerifiedViewerEntitlements(
            com.vidoora.backend.modules.membership.VerifiedGrantSource { account ->
                lookups++
                listOf(com.vidoora.backend.modules.membership.VerifiedViewerGrant(account.toString(),"MONTHLY","fixture-order",
                    now.minusSeconds(60),now.plusSeconds(3600),com.vidoora.backend.modules.membership.VerifiedPaymentState.PURCHASED,true))
            })
        val playback=PlaybackService(store,shareTokens=VideoShareTokens(JwtConfig(config)),entitlements=membership)
        denied { playback.asset(other,id,"720p/index.m3u8") }
        assertEquals(0,lookups);assertEquals(0,store.calls)
        assertTrue(playback.asset(owner,id,"master.m3u8").toString(Charsets.UTF_8).contains("720p"))
        playback.publish(owner,id,"UNLISTED")
        val token=playback.shareLink(owner,id).url.substringAfter('#')
        assertFalse(playback.asset(null,id,"master.m3u8",token).toString(Charsets.UTF_8).contains("720p"))
        val before=store.calls
        for(path in listOf("720p/index.m3u8","720p/segment_000000.ts")) {
            try { playback.asset(null,id,path,token);fail() } catch (_: PlaybackQualityRestricted) { }
        }
        assertEquals(before,store.calls)
        playback.unpublish(owner,id)
        denied { playback.asset(other,id,"720p/segment_000000.ts",token) }
    }

    @Test fun membershipHttpStatusIsGuestSafeAccountBoundAndSuspensionChecked()=testApplication {
        environment { config=MapApplicationConfig() }
        val now=java.time.Instant.now()
        val benefits=com.vidoora.backend.modules.membership.VerifiedViewerEntitlements(
            com.vidoora.backend.modules.membership.VerifiedGrantSource { account -> listOf(
                com.vidoora.backend.modules.membership.VerifiedViewerGrant(account.toString(),"MONTHLY","fixture-order",
                    now.minusSeconds(60),now.plusSeconds(3600),com.vidoora.backend.modules.membership.VerifiedPaymentState.PURCHASED,true)) })
        application {
            configureSerialization();configureSecurity(JwtConfig(config))
            routing { membershipRoutes(AuthService(JwtConfig(config)),benefits) }
        }
        val guest=client.get("/api/v1/membership")
        assertEquals(HttpStatusCode.OK,guest.status);assertEquals("no-store",guest.headers[HttpHeaders.CacheControl])
        assertTrue(guest.bodyAsText().contains("FREE"));assertFalse(guest.bodyAsText().contains(owner))
        val jwt=JwtConfig(config).generateAccessToken(owner,"fixture@example.test","USER")
        val paid=client.get("/api/v1/membership") { header(HttpHeaders.Authorization,"Bearer $jwt") }
        assertEquals(HttpStatusCode.OK,paid.status);assertTrue(paid.bodyAsText(),paid.bodyAsText().contains("MONTHLY"));assertTrue(paid.bodyAsText(),paid.bodyAsText().contains(owner))
        DatabaseFactory.dbQuery { UsersTable.update({ UsersTable.id eq UUID.fromString(owner) }) { it[isSuspended]=true } }
        assertEquals(HttpStatusCode.Forbidden,client.get("/api/v1/membership") { header(HttpHeaders.Authorization,"Bearer $jwt") }.status)
    }

    @Test fun stoppingSharingDuringEntitlementVerificationWinsBeforeStorageRead()=runBlocking {
        val id=video();service.publish(owner,id,"UNLISTED")
        val token=service.shareLink(owner,id).url.substringAfter('#')
        val before=store.calls // Publishing already validates the stored master once.
        val entered=kotlinx.coroutines.CompletableDeferred<Unit>();val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        val benefits=com.vidoora.backend.modules.membership.ViewerEntitlements { account ->
            entered.complete(Unit);release.await();com.vidoora.backend.modules.membership.ViewerEntitlement(account)
        }
        val playback=PlaybackService(store,shareTokens=VideoShareTokens(JwtConfig(config)),entitlements=benefits)
        val reading=async { runCatching { playback.asset(other,id,"master.m3u8",token) } }
        entered.await();service.unpublish(owner,id);release.complete(Unit)
        assertTrue(reading.await().exceptionOrNull() is PlaybackDenied);assertEquals(before,store.calls)
    }
}

