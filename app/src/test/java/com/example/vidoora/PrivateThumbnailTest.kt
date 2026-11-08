package com.example.vidoora

import android.graphics.Bitmap
import com.example.vidoora.feature.playback.PrivateThumbnailLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PrivateThumbnailTest {
    private val id="11111111-1111-1111-1111-111111111111"
    private fun jpeg(width: Int=480,height: Int=270)=ByteArrayOutputStream().also {
        Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG,80,it)
    }.toByteArray()
    @Test fun sharedPreviewUsesCapabilityHeaderAndNeverUrlParameters()=runBlocking {
        var reads=0
        val client=HttpClient(MockEngine { request ->
            reads++;assertEquals("fixture.capability.value",request.headers["X-Vidoora-Share"])
            assertTrue(request.url.parameters.isEmpty());assertNull(request.url.fragment.takeIf { it.isNotEmpty() })
            respond(jpeg(),headers=headersOf(HttpHeaders.ContentType,"image/jpeg"))
        })
        try { assertNotNull(PrivateThumbnailLoader(client) { "fixture.capability.value" }.load(id));assertEquals(1,reads) }
        finally { client.close() }
    }
    @Test fun privatePreviewUsesOnlyOwnerApiAndDecodesBoundedJpeg()=runBlocking {
        var requests=0
        val fixture=jpeg()
        assertEquals("fixture JPEG magic",255,fixture[0].toInt() and 255)
        assertEquals("fixture JPEG SOI",216,fixture[1].toInt() and 255)
        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeByteArray(fixture,0,fixture.size,bounds)
        assertEquals("fixture width",480,bounds.outWidth); assertEquals("fixture height",270,bounds.outHeight)
        // Decoder MIME is optional in legacy Robolectric; JPEG bytes and bounds are authoritative.
        assertTrue(bounds.outMimeType==null || bounds.outMimeType.equals("image/jpeg",ignoreCase=true))
        val client=HttpClient(MockEngine { request ->
            requests++; assertTrue(request.url.encodedPath.endsWith("/$id/thumbnail")); assertTrue(request.url.parameters.isEmpty())
            respond(jpeg(),headers=headersOf(HttpHeaders.ContentType,"image/jpeg"))
        })
        try { val image=PrivateThumbnailLoader(client).load(id); assertNotNull(image); assertEquals(480,image!!.width); assertEquals(270,image.height); assertEquals(1,requests) }
        finally { client.close() }
    }
    @Test fun deniedMalformedAndOversizedImagesStayPlaceholders()=runBlocking {
        for((status,type,body) in listOf(Triple(HttpStatusCode.NotFound,"application/json",byteArrayOf()),
            Triple(HttpStatusCode.OK,"image/jpeg",byteArrayOf(1,2,3,4)),Triple(HttpStatusCode.OK,"image/png",jpeg()),
            Triple(HttpStatusCode.OK,"image/jpeg",ByteArray(256*1024+1)),Triple(HttpStatusCode.OK,"image/jpeg",jpeg(1000,1000)))) {
            val client=HttpClient(MockEngine { respond(body,status,headersOf(HttpHeaders.ContentType,type)) })
            try { assertNull(PrivateThumbnailLoader(client).load(id)) } finally { client.close() }
        }
    }
    @Test fun invalidIdsNeverReachNetworkAndImagesAreNotReusedAcrossLoads()=runBlocking {
        var requests=0
        val client=HttpClient(MockEngine { requests++; respond(jpeg(),headers=headersOf(HttpHeaders.ContentType,"image/jpeg")) })
        try {
            val loader=PrivateThumbnailLoader(client)
            assertNull(loader.load("../private")); assertEquals(0,requests)
            assertNotNull(loader.load(id)); assertNotNull(loader.load(id)); assertEquals(2,requests)
        } finally { client.close() }
    }
}


