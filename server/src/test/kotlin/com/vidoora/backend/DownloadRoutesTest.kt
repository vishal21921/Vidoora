package com.vidoora.backend

import com.vidoora.backend.infrastructure.*
import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.downloads.*
import com.vidoora.backend.modules.membership.*
import com.vidoora.backend.modules.playback.*
import com.vidoora.backend.modules.video.VideosTable
import com.vidoora.backend.plugins.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.client.request.*
import io.ktor.client.call.body
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DownloadRoutesTest {
    @Test fun authenticatedTransferAndCompletionUseServerObservedAssets()=testApplication {
        environment{config=MapApplicationConfig()}
        val config=MapApplicationConfig("jwt.secret" to "fixture-secret-at-least-32-characters-long",
            "database.jdbcUrl" to "jdbc:h2:mem:vidoora_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL","database.driverClassName" to "org.h2.Driver",
            "database.username" to "sa","database.password" to "","database.allowInMemoryFallback" to "true","r2.mockMode" to "true")
        DatabaseFactory.init(config);val jwt=JwtConfig(config);val auth=AuthService(jwt)
        suspend fun register(): String{val suffix=UUID.randomUUID().toString().take(8);return auth.register(RegisterRequest("http_$suffix","$suffix@example.test","Password123!","Fixture")).user.id}
        val owner=register();val other=register();val video=UUID.randomUUID();val expiry=Instant.now().plusSeconds(3600)
        DatabaseFactory.dbQuery{VideosTable.insert{it[id]=video;it[uploaderId]=UUID.fromString(owner);it[title]="HTTP fixture";it[publicationState]="PROCESSED";
            it[processedBucket]="vidoora-test-download";it[masterPlaylistKey]="processed/$video/${UUID.randomUUID()}/master.m3u8"}}
        val storage=object:S3StorageService(config){override fun readProcessed(bucket: String,key: String,maxBytes: Int): ByteArray=when {
            key.endsWith("master.m3u8")->"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n480p/index.m3u8\n".toByteArray()
            key.endsWith("index.m3u8")->"#EXTM3U\n#EXTINF:6,\nsegment_000000.ts\n#EXT-X-ENDLIST\n".toByteArray()
            else->byteArrayOf(1,2,3)
        }}
        val benefits=ViewerEntitlements{ViewerEntitlement(it,"WEEK",true,null,expiry.toString(),60,5)}
        val playback=PlaybackService(storage,entitlements=benefits)
        val service=PrivateDownloads(playback,VerifiedGrantSource{listOf(VerifiedViewerGrant(owner,"WEEK","http-period",Instant.now().minusSeconds(60),expiry,VerifiedPaymentState.PURCHASED,true))},benefits=benefits)
        application{configureSerialization();configureSecurity(jwt);configureStatusPages();routing{downloadRoutes(service)}}
        fun bearer(account: String)=jwt.generateAccessToken(account,"fixture@example.test","USER")
        assertEquals(HttpStatusCode.Unauthorized,client.post("/api/v1/downloads/video/$video").status)
        val accepted=client.post("/api/v1/downloads/video/$video"){bearerAuth(bearer(owner));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001")}
        assertEquals(HttpStatusCode.OK,accepted.status);assertEquals("no-store",accepted.headers[HttpHeaders.CacheControl])
        val m=Json.decodeFromJsonElement<DownloadManifest>(Json.parseToJsonElement(accepted.bodyAsText()).jsonObject["data"]!!)
        val base="/api/v1/downloads/${m.id}"
        assertEquals(HttpStatusCode.Forbidden,client.get("$base/asset"){bearerAuth(bearer(other));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001");parameter("path",m.entry)}.status)
        assertEquals(HttpStatusCode.Forbidden,client.get("$base/asset"){bearerAuth(bearer(owner));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001");parameter("path","../../raw.mp4")}.status)
        assertEquals(HttpStatusCode.Conflict,client.post("$base/complete"){bearerAuth(bearer(owner));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001");contentType(ContentType.Application.Json);setBody("""{"hashes":{}}""")}.status)
        val proofs=buildJsonObject {for(path in m.paths){val reply=client.get("$base/asset"){bearerAuth(bearer(owner));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001");parameter("path",path)};assertEquals(HttpStatusCode.OK,reply.status);put(path,digest(reply.body<ByteArray>()))}}
        var result=client.post("$base/complete"){bearerAuth(bearer(owner));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001");contentType(ContentType.Application.Json);setBody(buildJsonObject{put("hashes",proofs)}.toString())}
        if(result.status==HttpStatusCode.Conflict){delay(100);result=client.post("$base/complete"){bearerAuth(bearer(owner));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001");contentType(ContentType.Application.Json);setBody(buildJsonObject{put("hashes",proofs)}.toString())}}
        assertEquals(HttpStatusCode.OK,result.status);assertTrue(result.bodyAsText().contains("validForSeconds"))
        assertEquals(HttpStatusCode.OK,client.delete(base){bearerAuth(bearer(owner));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001")}.status)
        assertEquals(HttpStatusCode.Forbidden,client.get("$base/asset"){bearerAuth(bearer(owner));header("X-Vidoora-Download","00000000-0000-0000-0000-000000000001");parameter("path",m.entry)}.status)
        storage.close()
    }
}
