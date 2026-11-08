package com.example.vidoora.feature.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream

/** Owner/capability-authorized, bounded previews. No public URLs or disk/account-shared image cache. */
class PrivateThumbnailLoader(private val client: HttpClient,private val token: (String)->String?={null}) {
    private val reads=Semaphore(4)
    suspend fun load(videoId: String, capability: String? = token(videoId), adminAnalytics: Boolean = false): Bitmap? {
        if(!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(videoId)) return null
        return reads.withPermit {
            try {
                val endpoint = if (adminAnalytics) "api/v1/analytics/videos/$videoId/thumbnail" else "api/v1/videos/$videoId/thumbnail"
                client.prepareGet(endpoint) { capability?.let { header("X-Vidoora-Share",it) } }.execute { response ->
                if(response.status.value!=200 || response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.equals("image/jpeg",ignoreCase=true)!=true) return@execute null
                val limit=256*1024
                val length=response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if(length!=null && length !in 4..limit.toLong()) return@execute null
                val bytes=ByteArrayOutputStream()
                val channel=response.bodyAsChannel(); val buffer=ByteArray(8192)
                try {
                    while(true) {
                        val size=channel.readAvailable(buffer,0,buffer.size)
                        if(size<0) break
                        if(size==0) continue
                        check(bytes.size()+size<=limit)
                        bytes.write(buffer,0,size)
                    }
                } finally { channel.cancel(null) }
                val data=bytes.toByteArray()
                if(data.size<4 || data[0]!=0xff.toByte() || data[1]!=0xd8.toByte()) return@execute null
                withContext(Dispatchers.Default) {
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeByteArray(data,0,data.size,bounds)
                    if(bounds.outWidth !in 1..480 || bounds.outHeight !in 1..270 || (bounds.outMimeType!=null && !bounds.outMimeType.equals("image/jpeg",ignoreCase=true))) null
                    else BitmapFactory.decodeByteArray(data,0,data.size)
                }
                }
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
}


