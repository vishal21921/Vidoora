package com.vidoora.backend

import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.deletion.*
import io.ktor.server.config.MapApplicationConfig
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VideoDeletionWorkerTest {
    @Test fun deletionKeysMustBelongToExactlyThisVideo() {
        val id=UUID.randomUUID()
        DeletionPaths.checkRaw(id,null); DeletionPaths.checkRaw(id,"raw/$id.mp4")
        for(key in listOf("raw/${UUID.randomUUID()}.mp4","raw/$id.mp4/extra","../raw/$id.mp4","raw/$id.exe","")) {
            try { DeletionPaths.checkRaw(id,key); fail("Unsafe raw deletion key accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun failedCleanupNeverMarksStorageDeletedAndCanBeRetried() {
        val id=UUID.randomUUID(); val claim=DeletionClaim(id,UUID.randomUUID(),"raw/$id.mp4",listOf("private-fixture"))
        var complete=false; var failed=0; var calls=0; var fail=true
        val repo=object : VideoDeletionRepository {
            override fun claim()=claim
            override fun heartbeat(claim: DeletionClaim)=true
            override fun complete(claim: DeletionClaim): Boolean { complete=true; return true }
            override fun fail(claim: DeletionClaim) { failed++ }
        }
        val storage=object : S3StorageService(MapApplicationConfig("r2.mockMode" to "true")) {
            override fun deleteVideoObjects(videoId: UUID,rawKey: String?,buckets: List<String>,alive: ()->Boolean) {
                assertEquals(id,videoId); assertEquals("raw/$id.mp4",rawKey); assertTrue(alive()); calls++
                if(fail) throw java.io.IOException("synthetic provider failure")
            }
        }
        storage.use {
            val worker=VideoDeletionWorker(repo,it)
            worker.runOne(); assertFalse(complete); assertEquals(1,failed)
            fail=false; worker.runOne(); assertTrue(complete); assertEquals(2,calls)
        }
    }
}
