package com.example.vidoora.feature.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.vidoora.core.network.ApiConfig
import com.example.vidoora.core.network.MediaEdgePolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException

internal class PlaybackHttpFailure(val status: Int) : IOException("Playback request failed")

/** Uses the existing refresh-capable API client, never a storage URL or copied access token. */
@UnstableApi
class AuthenticatedMediaDataSource(private val client: HttpClient,private val shareToken: String?=null,
    private val edgeBaseUrl: String=com.example.vidoora.BuildConfig.MEDIA_EDGE_BASE_URL) : BaseDataSource(true) {
    private var delegate: ByteArrayDataSource?=null
    private var opened=false
    private var currentUri: Uri?=null
    override fun open(dataSpec: DataSpec): Long {
        val requested=Url(dataSpec.uri.toString())
        val origin=Url(ApiConfig.BASE_URL)
        if(requested.host!=origin.host || requested.port!=origin.port || requested.protocol!=origin.protocol ||
            !Regex("/api/v1/videos/[0-9a-f-]{36}/playback/(?:master\\.m3u8|[0-9]{1,3}p/(?:index\\.m3u8|segment_[0-9]{6}\\.ts))").matches(requested.encodedPath) ||
            requested.parameters.entries().isNotEmpty() || !requested.user.isNullOrEmpty() || !requested.password.isNullOrEmpty() || requested.fragment.isNotEmpty()) throw IOException("Unsupported playback address")
        transferInitializing(dataSpec)
        currentUri=dataSpec.uri; opened=true
        // Include network download time in Media3's bandwidth measurement.
        transferStarted(dataSpec)
        val bytes=try { runBlocking(Dispatchers.IO) {
            client.prepareGet(MediaEdgePolicy.target(requested,edgeBaseUrl)) { shareToken?.let { header("X-Vidoora-Share",it) } }.execute { response ->
                if(response.status.value!=200) throw PlaybackHttpFailure(response.status.value)
                val limit=if(requested.encodedPath.endsWith(".m3u8")) 256*1024 else 16*1024*1024
                val channel=response.bodyAsChannel()
                val output=ByteArrayOutputStream()
                val buffer=ByteArray(64*1024)
                while(true) {
                    val read=channel.readAvailable(buffer,0,buffer.size)
                    if(read<0) break
                    if(output.size()+read>limit) throw IOException("Playback asset too large")
                    output.write(buffer,0,read)
                }
                output.toByteArray()
            }
        } } catch (failure: Exception) {
            close()
            throw IOException("Unable to load video. Check your connection and retry.", failure)
        }
        val source=ByteArrayDataSource(bytes)
        val length=try { source.open(dataSpec) } catch(e: IOException) { close(); throw e }
        delegate=source
        return length
    }
    override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
        if(length==0) return 0
        val count=delegate?.read(buffer,offset,length) ?: C.RESULT_END_OF_INPUT
        if(count>0) bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri?=currentUri
    override fun close() {
        delegate?.close(); delegate=null; currentUri=null
        if(opened) { opened=false; transferEnded() }
    }
    class Factory(private val client: HttpClient,private val shareToken: String?=null) : DataSource.Factory {
        override fun createDataSource(): DataSource=AuthenticatedMediaDataSource(client,shareToken)
    }
}

