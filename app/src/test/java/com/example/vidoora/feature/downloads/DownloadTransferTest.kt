package com.example.vidoora.feature.downloads

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DownloadTransferTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val account=UUID.randomUUID().toString();private var identity: String?="user:$account"
    private val root=File(context.cacheDir,"transfer-fixture-${UUID.randomUUID()}").apply{mkdirs()}
    private val store=OfflineStore(root,{SecretKeySpec(ByteArray(32){it.toByte()},"AES")},{identity},{4},SystemClock::elapsedRealtime,System::currentTimeMillis)
    private fun manifest(video: String)=OfflineManifest(UUID.randomUUID().toString(),video,"Fixture",6,"fixture-version","480p/index.m3u8",listOf("480p/index.m3u8","480p/segment_000000.ts"))
    @Test fun interruptedTransferResumesOnlyMissingAssetsAndCompletesWithProofs()=runBlocking {
        ShadowStatFs.registerStats(root.absolutePath,1000000,900000,900000)
        val item=store.create(UUID.randomUUID().toString(),"fixture-capability");val m=manifest(item.videoId);val reads=mutableListOf<String>();var proofSeen=false
        val client=HttpClient(MockEngine {request->
            val body=when {
                request.method==HttpMethod.Delete->{assertEquals(item.clientId,request.headers["X-Vidoora-Download"]);"{}"}
                request.url.encodedPath.endsWith("/asset")->{val path=request.url.parameters["path"]!!;reads+=path;"fixture-bytes-$path"}
                request.url.encodedPath.endsWith("/complete")->{proofSeen=true;"""{"success":true,"data":{"validForSeconds":60,"expiresAt":"2026-10-31T00:00:00Z"}}"""}
                else->"""{"success":true,"data":${Json.encodeToString(m)}}"""
            }
            if(request.method!=HttpMethod.Delete) assertEquals("fixture-capability",request.headers["X-Vidoora-Share"])
            respond(body,headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }){defaultRequest{url("https://fixture.test/");contentType(ContentType.Application.Json)};install(ContentNegotiation){json()}}
        try {
            val repository=DownloadRepository(store,client,context)
            repository.transfer(account,item.id){if(reads.size==1) store.update(account,item.id){it.copy(state="PAUSED",cancelPending=true,cancellations=listOf(CancelTransfer(m.id,it.clientId)))}}
            assertEquals(1,reads.size);assertFalse(proofSeen)
            store.update(account,item.id){it.copy(state="QUEUED",clientId=UUID.randomUUID().toString())}
            repository.transfer(account,item.id){}
            assertEquals(m.paths,reads);assertTrue(proofSeen);assertEquals("COMPLETE",store.get(account,item.id)!!.state)
            assertTrue(store.valid(store.get(account,item.id)!!))
        }finally{client.close()}
    }
    @Test fun accountChangeDuringDeliveryCannotWriteTheForeignResponse()=runBlocking {
        ShadowStatFs.registerStats(root.absolutePath,1000000,900000,900000)
        val item=store.create(UUID.randomUUID().toString(),null);val m=manifest(item.videoId)
        val client=HttpClient(MockEngine {request->
            if(request.url.encodedPath.endsWith("/asset")){identity="user:${UUID.randomUUID()}";respond("bytes")}
            else respond("""{"success":true,"data":${Json.encodeToString(m)}}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }){defaultRequest{url("https://fixture.test/");contentType(ContentType.Application.Json)};install(ContentNegotiation){json()}}
        try{DownloadRepository(store,client,context).transfer(account,item.id){};assertTrue(store.get(account,item.id)!!.hashes.isEmpty());assertTrue(store.list().isEmpty())}finally{client.close()}
    }
}
