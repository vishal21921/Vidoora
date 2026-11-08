package com.example.vidoora.feature.downloads

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
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
class OfflineStoreTest {
    private val account=UUID.randomUUID().toString();private var identity: String?="user:$account"
    private var boot=5L;private var elapsed=1000L;private var wall=100000L
    private val root=File(ApplicationProvider.getApplicationContext<Context>().cacheDir,"offline-test-${UUID.randomUUID()}").apply{mkdirs()}
    private val key=SecretKeySpec(ByteArray(32){it.toByte()},"AES")
    private fun store()=OfflineStore(root,{key},{identity},{boot},{elapsed},{wall})
    private fun complete(store: OfflineStore): OfflineItem {
        ShadowStatFs.registerStats(root.absolutePath,1000000,900000,900000)
        val item=store.create(UUID.randomUUID().toString(),"fixture-private-capability")
        val manifest=OfflineManifest(UUID.randomUUID().toString(),item.videoId,"Private fixture title",7,"fixture-version","480p/index.m3u8",listOf("480p/index.m3u8","480p/segment_000000.ts"))
        store.update(account,item.id){it.copy(state="DOWNLOADING",manifest=manifest)}
        assertTrue(store.chunk(account,item.id,manifest.entry,"#EXTM3U\n#EXTINF:7,\nsegment_000000.ts\n#EXT-X-ENDLIST\n".toByteArray()))
        assertTrue(store.chunk(account,item.id,manifest.paths[1],"fixture-video-bytes".toByteArray()))
        assertTrue(store.anchor(account,item.id,OfflineLease(60,"unused-server-expiry-label"),elapsed,wall))
        return store.get(account,item.id)!!
    }
    @Test fun filesAndMetadataAreEncryptedAndSurviveStoreRecreation() {
        val store=store();val item=complete(store)
        val raw=root.walkTopDown().filter{it.isFile}.flatMap{it.readBytes().asIterable()}.toList().toByteArray().toString(Charsets.ISO_8859_1)
        for(secret in listOf("Private fixture title","fixture-private-capability","fixture-video-bytes")) assertFalse(raw.contains(secret))
        val restored=store();assertEquals(item.id,restored.list().single().id)
        assertEquals("fixture-video-bytes",restored.read(item,item.manifest!!.paths[1]).toString(Charsets.UTF_8))
        assertFalse(item.toString().contains("fixture-private-capability"))
    }
    @Test fun accountSwitchAndLogoutCannotReadOrListAnotherAccountsFiles() {
        val store=store();val item=complete(store)
        identity="user:${UUID.randomUUID()}";assertTrue(store.list().isEmpty());assertFalse(store.valid(item))
        try{store.read(item,item.manifest!!.entry);fail()}catch(_: Exception){}
        identity=null;assertTrue(store.list().isEmpty());assertFalse(store.valid(item))
        identity="user:$account";assertTrue(store.valid(item))
    }
    @Test fun bootRollbackWallRollbackAndExpiryRequireOnlineValidation() {
        val store=store();val item=complete(store);assertTrue(store.valid(item))
        boot=6;assertFalse(store.valid(item));boot=5
        elapsed=999;assertFalse(store.valid(item));elapsed=1000
        wall=99999;assertFalse(store.valid(item));wall=100000
        elapsed=61000;assertFalse(store.valid(item));elapsed=1000
        wall=160000;assertFalse(store.valid(item))
        boot=-1;assertFalse(store.valid(item))
    }
    @Test fun tamperedCiphertextAndTraversalCannotBePlayed() {
        val store=store();val item=complete(store);val path=item.manifest!!.paths[1]
        val file=File(root,"$account/${item.id}/${sha(path.toByteArray())}.bin")
        val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertFalse(store.hasChunk(item,path))
        try{store.read(item,path);fail()}catch(_: Exception){}
        try{store.read(item,"../../outside.mp4");fail()}catch(_: Exception){}
    }
    @Test fun replacementLinksAndRepairedChunksDoNotCreateDuplicatesOrDoubleCountBytes() {
        val store=store();val item=complete(store)
        val reopened=store.create(item.videoId,"replacement-capability")
        assertEquals(item.id,reopened.id);assertEquals("replacement-capability",reopened.shareToken)
        val before=store.get(account,item.id)!!.bytes
        store.update(account,item.id){it.copy(state="DOWNLOADING")}
        val path=item.manifest!!.paths[1]
        assertTrue(store.chunk(account,item.id,path,"fixture-video-bytes".toByteArray()))
        assertEquals(before,store.get(account,item.id)!!.bytes)
    }
    @Test fun staleWorkerChunksAndLeasesCannotOverwriteANewerTransfer() {
        val store=store();val item=complete(store)
        val newer=java.util.UUID.randomUUID().toString()
        store.update(account,item.id){it.copy(state="DOWNLOADING",clientId=newer)}
        assertFalse(store.chunk(account,item.id,item.manifest!!.entry,byteArrayOf(1),item.manifest.version,item.clientId))
        assertFalse(store.anchor(account,item.id,OfflineLease(60,"fixture-expiry"),elapsed,wall,item.clientId,item.manifest.id))
        assertEquals(newer,store.get(account,item.id)!!.clientId)
    }
    @Test fun removalAndPauseFenceLateTransferWrites() {
        val store=store();val item=complete(store)
        store.update(account,item.id){it.copy(state="PAUSED",validMs=0)}
        assertFalse(store.chunk(account,item.id,item.manifest!!.entry,byteArrayOf(1)))
        store.update(account,item.id){it.copy(state="REMOVING",cancelPending=true)}
        assertTrue(store.list().isEmpty());assertEquals(1,store.list(true).size)
        store.eraseChunks(account,item.id);store.erase(account,item.id)
        assertNull(store.get(account,item.id));assertFalse(store.chunk(account,item.id,item.manifest!!.entry,byteArrayOf(1)))
    }
}
