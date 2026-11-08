package com.example.vidoora.feature.downloads

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import java.io.IOException

@UnstableApi
class OfflineDataSource(private val store: OfflineStore,private val account: String,private val id: String): BaseDataSource(false) {
    private var delegate: ByteArrayDataSource?=null;private var uri: Uri?=null;private var opened=false
    override fun open(spec: DataSpec): Long {
        val parsed=spec.uri
        if(parsed.scheme!="vidoora-offline" || parsed.host!=id || parsed.query!=null || parsed.fragment!=null) throw IOException("Unsupported offline address")
        val row=store.get(account,id) ?: throw IOException("Download removed")
        val path=parsed.path?.removePrefix("/") ?: throw IOException("Invalid offline asset")
        val bytes=try{store.read(row,path)}catch(_: Exception){throw IOException("Download needs validation or repair")}
        transferInitializing(spec);uri=parsed
        val source=ByteArrayDataSource(bytes);val count=source.open(spec);delegate=source;opened=true;transferStarted(spec);return count
    }
    override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
        if(length==0) return 0
        val row=store.get(account,id) ?: throw IOException("Download removed")
        if(!store.valid(row)) throw IOException("Download access expired")
        val count=delegate?.read(buffer,offset,length) ?: C.RESULT_END_OF_INPUT
        if(count>0) bytesTransferred(count);return count
    }
    override fun getUri(): Uri?=uri
    override fun close(){delegate?.close();delegate=null;uri=null;if(opened){opened=false;transferEnded()}}
    class Factory(private val store: OfflineStore,private val account: String,private val id: String): DataSource.Factory {
        override fun createDataSource(): DataSource=OfflineDataSource(store,account,id)
    }
}
