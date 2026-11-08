package com.example.vidoora.feature.downloads

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.vidoora.core.security.SecureStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class OfflineManifest(val id: String,val videoId: String,val title: String,val durationSeconds: Int,
    val version: String,val entry: String,val paths: List<String>)
@Serializable data class OfflineLease(val validForSeconds: Long,val expiresAt: String)
@Serializable internal data class Proof(val hashes: Map<String,String>)
@Serializable data class CancelTransfer(val reservationId: String,val clientId: String)
@Serializable data class OfflineItem(val id: String,val account: String,val videoId: String,val shareToken: String?=null,
    val manifest: OfflineManifest?=null,val state: String="QUEUED",val hashes: Map<String,String> = emptyMap(),
    val bytes: Long=0,val sizes: Map<String,Long> = emptyMap(),val boot: Long=-1,val elapsed: Long=0,val wall: Long=0,val validMs: Long=0,
    val clientId: String=id,val cancellations: List<CancelTransfer> = emptyList(),val cancelPending: Boolean=false,val message: String?=null) {
    override fun toString()="OfflineItem(redacted)"
}
internal fun sha(bytes: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
object OfflineLeasePolicy {
    fun valid(item: OfflineItem,boot: Long,elapsed: Long,wall: Long): Boolean = item.state=="COMPLETE" && item.boot>=0 && item.boot==boot &&
        item.elapsed>=0 && elapsed>=item.elapsed && item.validMs in 1..86_400_000 && elapsed-item.elapsed<item.validMs &&
        wall>=item.wall && wall-item.wall<item.validMs
}
/** Every file, including titles/share capabilities/leases, is AEAD-encrypted in noBackupFilesDir. */
@Singleton
class OfflineStore internal constructor(private val root: File,private val key: ()->SecretKey,
    private val identity: ()->String?,private val boot: ()->Long,private val elapsed: ()->Long,private val wall: ()->Long) {
    @Inject constructor(@ApplicationContext context: Context,storage: SecureStorageManager):this(
        File(context.noBackupFilesDir,"private_offline_v1"),{offlineKey()},storage::watchIdentity,
        {try{Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT).toLong()}catch(_: Exception){-1L}},SystemClock::elapsedRealtime,System::currentTimeMillis)
    val changes=MutableStateFlow(0L)
    private val lock=Any()
    fun account(): String?=identity()?.takeIf{it.startsWith("user:")}?.removePrefix("user:")?.takeIf{runCatching{UUID.fromString(it).toString()==it}.getOrDefault(false)}
    private fun dir(account: String,id: String): File {
        require(UUID.fromString(account).toString()==account && UUID.fromString(id).toString()==id)
        return File(File(root,account),id)
    }
    private fun crypt(account: String,id: String,path: String,bytes: ByteArray,encrypt: Boolean): ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        if(encrypt) cipher.init(Cipher.ENCRYPT_MODE,key()) else {require(bytes.size>=28);cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))}
        cipher.updateAAD("vidoora-offline-v1|$account|$id|$path".toByteArray(Charsets.UTF_8))
        return if(encrypt) cipher.iv+cipher.doFinal(bytes) else cipher.doFinal(bytes,12,bytes.size-12)
    }
    private fun write(file: File,bytes: ByteArray) {
        file.parentFile!!.mkdirs();val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try{stream.write(bytes);atomic.finishWrite(stream)}catch(e: Exception){atomic.failWrite(stream);throw e}
    }
    private fun metadata(account: String,id: String)=File(dir(account,id),"record.bin")
    private fun load(account: String,id: String): OfflineItem? {
        val file=metadata(account,id);if(!file.exists()) return null
        require(file.length()<=4*1024*1024)
        val item=Json.decodeFromString<OfflineItem>(crypt(account,id,"record",AtomicFile(file).readFully(),false).toString(Charsets.UTF_8))
        require(item.account==account && item.id==id);return item
    }
    private fun save(item: OfflineItem) {
        write(metadata(item.account,item.id),crypt(item.account,item.id,"record",Json.encodeToString(item).toByteArray(Charsets.UTF_8),true))
        changes.value++
    }
    fun list(includeRemoving: Boolean=false): List<OfflineItem> = synchronized(lock) {
        val account=account() ?: return@synchronized emptyList()
        File(root,account).listFiles().orEmpty().filter{it.isDirectory && runCatching{UUID.fromString(it.name)}.isSuccess}.take(100)
            .mapNotNull{runCatching{load(account,it.name)}.getOrNull()}.filter{includeRemoving || it.state!="REMOVING"}
    }
    fun get(account: String,id: String): OfflineItem?=synchronized(lock){load(account,id)}
    fun create(video: String,token: String?): OfflineItem=synchronized(lock) {
        val account=account() ?: error("Sign in to download")
        require(UUID.fromString(video).toString()==video && (token==null || token.length<=8192))
        val existing=list().firstOrNull{it.videoId==video};if(existing!=null) return@synchronized existing.copy(shareToken=token).also{save(it)}
        require(list().size<60){"Remove an older download first"}
        OfflineItem(UUID.randomUUID().toString(),account,video,token).also{save(it)}
    }
    fun update(account: String,id: String,transform: (OfflineItem)->OfflineItem): OfflineItem?=synchronized(lock) {
        val current=load(account,id) ?: return@synchronized null
        transform(current).also{require(it.account==account && it.id==id);save(it)}
    }
    fun chunk(account: String,id: String,path: String,bytes: ByteArray,expectedVersion: String?=null,expectedClient: String?=null): Boolean=synchronized(lock) {
        val item=load(account,id) ?: return@synchronized false
        if((expectedVersion!=null && item.manifest?.version!=expectedVersion) || (expectedClient!=null && item.clientId!=expectedClient)) return@synchronized false
        if(this.account()!=account || item.state !in listOf("QUEUED","DOWNLOADING") || path !in item.manifest!!.paths) return@synchronized false
        val total=item.bytes-(item.sizes[path] ?: 0L)+bytes.size
        require(bytes.size<=16*1024*1024 && total<=2L*1024*1024*1024)
        root.mkdirs();if(android.os.StatFs(root.absolutePath).availableBytes<bytes.size+64L*1024*1024) throw IOException("Storage is full")
        val name=sha(path.toByteArray(Charsets.UTF_8))+".bin"
        write(File(dir(account,id),name),crypt(account,id,path,bytes,true))
        save(item.copy(hashes=item.hashes+(path to sha(bytes)),sizes=item.sizes+(path to bytes.size.toLong()),bytes=total));true
    }
    fun hasChunk(item: OfflineItem,path: String): Boolean=synchronized(lock) {
        runCatching {
            val file=File(dir(item.account,item.id),sha(path.toByteArray(Charsets.UTF_8))+".bin")
            file.isFile && file.length()<=16*1024*1024+28 && sha(crypt(item.account,item.id,path,AtomicFile(file).readFully(),false))==item.hashes[path]
        }.getOrDefault(false)
    }
    fun read(item: OfflineItem,path: String): ByteArray=synchronized(lock) {
        if(this.account()!=item.account || !valid(item) || path !in item.manifest!!.paths) throw IOException("Download needs online validation")
        val file=File(dir(item.account,item.id),sha(path.toByteArray(Charsets.UTF_8))+".bin")
        require(file.length()<=16*1024*1024+28)
        val bytes=crypt(item.account,item.id,path,AtomicFile(file).readFully(),false)
        if(sha(bytes)!=item.hashes[path]) throw IOException("Download file is damaged")
        bytes
    }
    fun valid(item: OfflineItem)=account()==item.account && OfflineLeasePolicy.valid(item,boot(),elapsed(),wall())
    fun anchor(account: String,id: String,lease: OfflineLease,start: Long,startWall: Long,expectedClient: String?=null,expectedReservation: String?=null): Boolean=synchronized(lock) {
        val item=load(account,id) ?: return@synchronized false
        if(this.account()!=account || item.state !in listOf("DOWNLOADING","QUEUED","COMPLETE")) return@synchronized false
        if((expectedClient!=null && item.clientId!=expectedClient) || (expectedReservation!=null && item.manifest?.id!=expectedReservation)) return@synchronized false
        require(lease.validForSeconds in 1..86400)
        if(boot()<0 || elapsed()<start || elapsed()-start>=lease.validForSeconds*1000) return@synchronized false
        save(item.copy(state="COMPLETE",boot=boot(),elapsed=start,wall=startWall,validMs=lease.validForSeconds*1000,cancelPending=false,message=null));true
    }
    fun eraseChunks(account: String,id: String)=synchronized(lock) {
        dir(account,id).listFiles().orEmpty().filter{it.name!="record.bin" && it.name!="record.bin.bak"}.forEach{it.delete()}
    }
    fun erase(account: String,id: String)=synchronized(lock) {dir(account,id).deleteRecursively();changes.value++;Unit}
    companion object {
        private fun offlineKey(): SecretKey=synchronized(OfflineStore::class.java) {
            val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            (store.getKey("vidoora_private_offline_v1",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("vidoora_private_offline_v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            }.generateKey()
        }
    }
}
