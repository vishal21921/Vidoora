package com.vidoora.backend

import com.vidoora.backend.infrastructure.*
import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.downloads.*
import com.vidoora.backend.modules.membership.*
import com.vidoora.backend.modules.playback.*
import com.vidoora.backend.modules.video.VideosTable
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class PrivateDownloadPostgresIntegrationTest {
    @Test fun migrationAndConcurrentQuotaReservationsAreDurableAcrossInstances()=runBlocking {
        assumeTrue(System.getenv("VIDOORA_POSTGRES_TEST")=="true")
        val url=checkNotNull(System.getenv("DATABASE_URL"));require(url.startsWith("jdbc:postgresql:"))
        val user=checkNotNull(System.getenv("DB_USERNAME"));val password=System.getenv("DB_PASSWORD") ?: ""
        val schema="vidoora_download_test_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("vidoora_download_test_[0-9a-f]{32}").matches(schema))
        DriverManager.getConnection(url,user,password).use{db->db.createStatement().use{it.execute("CREATE SCHEMA $schema")}}
        try {
            val config=MapApplicationConfig("database.jdbcUrl" to (url+if(url.contains('?')) "&currentSchema=$schema" else "?currentSchema=$schema"),
                "database.driverClassName" to "org.postgresql.Driver","database.username" to user,"database.password" to password,
                "jwt.secret" to "fixture-secret-at-least-32-characters-long","r2.mockMode" to "true")
            DatabaseFactory.init(config)
            val auth=AuthService(JwtConfig(config));val owner=auth.register(RegisterRequest("pg_fixture","fixture@example.test","Password123!","Fixture")).user.id
            val now=Instant.now();val expiry=now.plusSeconds(604800)
            val storage=object:S3StorageService(config) {
                override fun readProcessed(bucket: String,key: String,maxBytes: Int): ByteArray=when {
                    key.endsWith("master.m3u8")->"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n480p/index.m3u8\n".toByteArray()
                    key.endsWith("index.m3u8")->"#EXTM3U\n#EXTINF:6,\nsegment_000000.ts\n#EXT-X-ENDLIST\n".toByteArray()
                    else->byteArrayOf(1,2,3)
                }
            }
            val source=VerifiedGrantSource{listOf(VerifiedViewerGrant(owner,"WEEK","fixture-period",now.minusSeconds(10),expiry,VerifiedPaymentState.PURCHASED,true))}
            val benefits=ViewerEntitlements{ViewerEntitlement(it,"WEEK",true,null,expiry.toString(),60,5)}
            val playback=PlaybackService(storage,entitlements=benefits)
            fun service()=PrivateDownloads(playback,source,benefits=benefits)
            val ids=(1..12).map {
                val id=UUID.randomUUID()
                DatabaseFactory.dbQuery{VideosTable.insert{it[VideosTable.id]=id;it[uploaderId]=UUID.fromString(owner);it[title]="Fixture";it[publicationState]="PROCESSED";
                    it[processedBucket]="vidoora-test-download";it[masterPlaylistKey]="processed/$id/${UUID.randomUUID()}/master.m3u8"}}
                id.toString()
            }
            val results=ids.map{id->async(Dispatchers.IO){runCatching{service().reserve(owner,id,null)}}}.awaitAll()
            assertEquals(5,results.count{it.isSuccess});assertEquals(7,results.count{it.exceptionOrNull() is DownloadQuotaReached})
            val first=results.first{it.isSuccess}.getOrThrow();val fresh=service()
            assertEquals(first.id,fresh.reserve(owner,first.videoId,null).id)
            val proofs=first.paths.associateWith{path->val bytes=fresh.asset(owner,first.id,path,null);val hash=digest(bytes);fresh.delivered(owner,first.id,path,hash);hash}
            assertTrue(fresh.complete(owner,first.id,null,DownloadProof(proofs)).validForSeconds in 1..86400)
            fresh.cancel(owner,first.id)
            try{service().reserve(owner,ids[results.indexOfFirst{it.isFailure}],null);fail()}catch(_: DownloadQuotaReached){}
            val unused=results.filter{it.isSuccess}.map{it.getOrThrow()}.first{it.id!=first.id};fresh.cancel(owner,unused.id)
            assertNotNull(service().reserve(owner,ids[results.indexOfFirst{it.isFailure}],null))
            storage.close()
        }finally {
            DatabaseFactory.close()
            DriverManager.getConnection(url,user,password).use{db->db.createStatement().use{it.execute("DROP SCHEMA $schema CASCADE")}}
        }
    }
}
