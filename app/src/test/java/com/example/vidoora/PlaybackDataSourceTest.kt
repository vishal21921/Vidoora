package com.example.vidoora

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.example.vidoora.core.network.ApiConfig
import com.example.vidoora.feature.playback.AuthenticatedMediaDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PlaybackDataSourceTest {
    private val url=ApiConfig.BASE_URL.trimEnd('/')+"/api/v1/videos/11111111-1111-1111-1111-111111111111/playback/360p/segment_000000.ts"
    @Test fun mediaReadsRespectRequestedOffsetAndLength() {
        var requests=0
        val client=HttpClient(MockEngine { requests++; respond(byteArrayOf(1,2,3,4,5),headers=headersOf(HttpHeaders.ContentType,"video/mp2t")) })
        val source=AuthenticatedMediaDataSource(client)
        try {
            assertEquals(2L,source.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(2).setLength(2).build()))
            val bytes=ByteArray(4); assertEquals(2,source.read(bytes,0,4)); assertArrayEquals(byteArrayOf(3,4),bytes.copyOf(2))
            assertEquals(-1,source.read(bytes,0,4)); assertEquals(1,requests)
        } finally { source.close(); client.close() }
    }
    @Test fun foreignOriginAndNonPlaybackAddressesNeverReachNetwork() {
        var requests=0
        val client=HttpClient(MockEngine { requests++; respond("unexpected") })
        try {
            for(address in listOf("https://example.test/private",ApiConfig.BASE_URL+"api/v1/auth/login",url+"?token=example",url.replace("segment_000000.ts","../../raw/private.mp4"))) {
                val source=AuthenticatedMediaDataSource(client)
                try { source.open(DataSpec(Uri.parse(address))); fail("Unsafe media address accepted") } catch (_: IOException) { } finally { source.close() }
            }
            assertEquals(0,requests)
        } finally { client.close() }
    }
    @Test fun unauthorizedPlaybackHasSafeActionableError() {
        val client=HttpClient(MockEngine { respond("private provider details",HttpStatusCode.Unauthorized) })
        val source=AuthenticatedMediaDataSource(client)
        try { source.open(DataSpec(Uri.parse(url))); fail() }
        catch(e: IOException) { assertFalse(e.message!!.contains("private provider")); assertTrue(e.message!!.contains("retry")) }
        finally { source.close(); client.close() }
    }
    @Test fun shareCapabilityUsesHeaderAndNeverMediaUrl() {
        var correct=false
        val client=HttpClient(MockEngine { request ->
            correct=request.headers[HttpHeaders.Authorization]==null && request.headers["X-Vidoora-Share"]=="aaa.bbb.ccc" && request.url.parameters.isEmpty() && !request.url.toString().contains("aaa.bbb.ccc")
            respond(byteArrayOf(1,2,3))
        })
        val source=AuthenticatedMediaDataSource(client,"aaa.bbb.ccc")
        try { source.open(DataSpec(Uri.parse(url))); assertTrue(correct) } finally { source.close(); client.close() }
    }
    @Test fun oversizedManifestIsRejected() {
        val client=HttpClient(MockEngine { respond(ByteArray(256*1024+1)) })
        val source=AuthenticatedMediaDataSource(client)
        try { source.open(DataSpec(Uri.parse(url.substringBefore("360p/")+"master.m3u8"))); fail("Unbounded playlist accepted") }
        catch (_: IOException) { } finally { source.close(); client.close() }
    }
    @Test fun enabledEdgeMovesOnlySegmentsAndPreservesPlaylistOrigin() {
        val hosts=mutableListOf<String>()
        val client=HttpClient(MockEngine { request ->
            hosts+=request.url.host
            assertEquals("fixture",request.headers["X-Vidoora-Share"])
            assertTrue(request.url.parameters.isEmpty())
            respond(byteArrayOf(1,2,3))
        })
        val source=AuthenticatedMediaDataSource(client,"fixture","https://media.example/")
        try {
            source.open(DataSpec(Uri.parse(url)));assertEquals(Uri.parse(url),source.getUri());source.close()
            source.open(DataSpec(Uri.parse(url.substringBefore("360p/")+"master.m3u8")))
            assertEquals(listOf("media.example",Url(ApiConfig.BASE_URL).host),hosts)
        } finally { source.close();client.close() }
    }
}


