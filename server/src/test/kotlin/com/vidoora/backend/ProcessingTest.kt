package com.vidoora.backend

import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.processing.*
import io.ktor.server.config.MapApplicationConfig
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ProcessingTest {
    @Test fun landscapeRenditionsDoNotUpscale() {
        assertEquals(listOf(360,480,720),MediaPlan.renditions(MediaInfo(1920,1080,12.0,true)).map { it.height })
        assertEquals(listOf(Rendition(320,240,800000)),MediaPlan.renditions(MediaInfo(320,240,12.0,false)))
    }
    @Test fun portraitBoundsAndEvenDimensions() {
        val info = MediaInfo(1080,1920,12.0,true)
        val sizes = MediaPlan.renditions(info)
        assertTrue(sizes.all { it.width<=info.width && it.height<=720 && it.width%2==0 && it.height%2==0 })
        assertEquals(listOf(360,480,720),sizes.map { it.height })
    }
    @Test fun probeAccountsForRotationAndIgnoresCoverArt() {
        val info = MediaPlan.probe("""{"streams":[{"codec_type":"video","width":32,"height":32,"disposition":{"attached_pic":1}},{"codec_type":"video","width":1920,"height":1080,"sample_aspect_ratio":"1:1","side_data_list":[{"rotation":90}]},{"codec_type":"audio"}],"format":{"duration":"12.5"}}""")
        assertEquals(1080,info.width); assertEquals(1920,info.height); assertTrue(info.hasAudio)
    }
    @Test fun invalidMediaIsRejected() {
        for (info in listOf(MediaInfo(0,100,1.0,false),MediaInfo(8000,100,1.0,false),MediaInfo(100,100,Double.NaN,false),MediaInfo(100,100,7201.0,false))) {
            try { MediaPlan.renditions(info); fail("Invalid probe accepted") } catch (_: InvalidMedia) { }
        }
        try { MediaPlan.probe("""{"streams":[{"codec_type":"audio"}],"format":{"duration":"12"}}"""); fail() } catch (_: InvalidMedia) { }
    }
    @Test fun masterUsesRelativePrivatePathsAndPeakBandwidth() {
        val master = MediaPlan.master(listOf(Rendition(640,360,800000)),true)
        assertTrue(master.contains("360p/index.m3u8")); assertTrue(master.contains("BANDWIDTH=1527200"))
        assertFalse(master.contains("https:")); assertFalse(master.contains("r2:"))
    }
    private class Repo : ProcessingRepository {
        val claim = ProcessingClaim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"raw/test.mp4",3,"private-test","processed/${UUID.randomUUID()}/${UUID.randomUUID()}/")
        var valid = true; var completed = false; var failed: Boolean? = null
        override fun claim(bucket: String) = claim
        override fun heartbeat(claim: ProcessingClaim) = valid
        override fun complete(claim: ProcessingClaim,output: ProcessingOutput): Boolean { completed=valid; return valid }
        override fun fail(claim: ProcessingClaim,permanent: Boolean): Boolean { failed=permanent; return valid }
    }
    private class Store : S3StorageService(MapApplicationConfig("r2.mockMode" to "true")) {
        val uploaded = mutableListOf<String>()
        override fun downloadOriginal(objectKey: String,destination: Path,expectedBytes: Long) { Files.write(destination,byteArrayOf(1,2,3)) }
        override fun uploadProcessed(bucket: String,key: String,file: Path) { uploaded += key }
    }
    private fun output(dir: Path): ProcessingOutput {
        Files.createDirectories(dir.resolve("360p"))
        Files.writeString(dir.resolve("360p/segment_000000.ts"),"segment")
        Files.writeString(dir.resolve("360p/index.m3u8"),"playlist")
        Files.writeString(dir.resolve("master.m3u8"),"master")
        return ProcessingOutput(MediaInfo(640,360,1.0,false),listOf(Rendition(640,360,800000)),dir)
    }
    @Test fun workerUploadsMasterLastCommitsAndRemovesScratch() {
        val dir=Files.createTempDirectory("processing-test-"); val repo=Repo(); val store=Store()
        try {
            ProcessingWorker(repo,store,dir) { _,out,_ -> output(out) }.runOne()
            assertTrue(repo.completed); assertNull(repo.failed)
            assertTrue(store.uploaded.last().endsWith("master.m3u8"))
            Files.list(dir).use { assertEquals(0L,it.count()) }
        } finally { Files.deleteIfExists(dir) }
    }
    @Test fun lostLeaseCannotUploadOrCommit() {
        val dir=Files.createTempDirectory("processing-test-"); val repo=Repo(); val store=Store()
        try {
            ProcessingWorker(repo,store,dir) { _,out,_ -> output(out).also { repo.valid=false } }.runOne()
            assertFalse(repo.completed); assertTrue(store.uploaded.isEmpty())
            Files.list(dir).use { assertEquals(0L,it.count()) }
        } finally { Files.deleteIfExists(dir) }
    }
    @Test fun invalidMediaIsPermanentAndTransportFailureRetryable() {
        for (permanent in listOf(true,false)) {
            val dir=Files.createTempDirectory("processing-test-"); val repo=Repo(); val store=Store()
            try {
                ProcessingWorker(repo,store,dir) { _,_,_ -> if(permanent) throw InvalidMedia() else throw java.io.IOException() }.runOne()
                assertEquals(permanent,repo.failed); assertFalse(repo.completed)
                Files.list(dir).use { assertEquals(0L,it.count()) }
            } finally { Files.deleteIfExists(dir) }
        }
    }
    @Test fun cleanupRefusesPathsOutsideScratch() {
        val dir=Files.createTempDirectory("processing-test-")
        try { ProcessingWorker.removeAttemptDirectory(dir,dir.parent); fail("Unsafe cleanup accepted") }
        catch (_: IllegalArgumentException) { } finally { Files.deleteIfExists(dir) }
    }
}
