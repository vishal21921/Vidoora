package com.vidoora.backend

import com.vidoora.backend.infrastructure.*
import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.upload.*
import com.vidoora.backend.modules.video.*
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class UploadReliabilityTest {
    private val config = MapApplicationConfig(
        "jwt.secret" to "test-only-secret-at-least-32-characters-long",
        "database.jdbcUrl" to "jdbc:h2:mem:vidoora_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "database.driverClassName" to "org.h2.Driver", "database.username" to "sa", "database.password" to "",
        "database.allowInMemoryFallback" to "true",
        "r2.accessKeyId" to "fake-injected-storage", "r2.secretAccessKey" to "fake-injected-storage", "r2.mockMode" to "false"
    )
    private lateinit var owner: String
    private lateinit var other: String
    private lateinit var storage: FakeStorage
    private lateinit var service: UploadService
    @Before fun setUp() = runBlocking {
        DatabaseFactory.init(config)
        val auth = AuthService(JwtConfig(config))
        suspend fun register(): String {
            val suffix = UUID.randomUUID().toString().take(8)
            return auth.register(RegisterRequest("user_$suffix", "$suffix@example.test", "Password123!", "Test User")).user.id
        }
        owner = register(); other = register()
        DatabaseFactory.dbQuery {
            com.vidoora.backend.modules.user.UsersTable.update({com.vidoora.backend.modules.user.UsersTable.id eq UUID.fromString(owner)}) {
                it[com.vidoora.backend.modules.user.UsersTable.role]="CREATOR"
            }
        }
        storage = FakeStorage(config)
        service = UploadService(storage)
    }
    private suspend fun initiate(bytes: Long = UploadValidation.CHUNK_BYTES + 7L) = service.initiateUpload(owner, InitiateUploadRequest("Reliability test", fileSizeBytes = bytes, mimeType = "video/mp4", visibility = "PRIVATE"))
    private fun tags() = listOf(PartTagRequest(2, "\"part-2\""), PartTagRequest(1, "\"part-1\""))
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Request should have been rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    @Test fun ownershipVideoBindingAndRefreshBounds() = runBlocking {
        val upload = initiate()
        rejected { service.refreshUploadPartUrls(other, upload.videoId, RefreshPartsRequest(upload.uploadSessionId, listOf(1))) }
        rejected { service.getUploadStatus(other, upload.videoId) }
        rejected { service.completeUpload(other, upload.videoId, CompleteUploadRequest(upload.uploadSessionId, tags())) }
        rejected { service.abortUpload(other, upload.videoId, AbortUploadRequest(upload.uploadSessionId)) }
        for (numbers in listOf(emptyList(), listOf(0), listOf(3), listOf(1, 1))) rejected {
            service.refreshUploadPartUrls(owner, upload.videoId, RefreshPartsRequest(upload.uploadSessionId, numbers))
        }
        val wrongVideo = UUID.randomUUID().toString()
        rejected { service.refreshUploadPartUrls(owner, wrongVideo, RefreshPartsRequest(upload.uploadSessionId, listOf(1))) }
        rejected { service.completeUpload(owner, wrongVideo, CompleteUploadRequest(upload.uploadSessionId, tags())) }
        rejected { service.abortUpload(owner, wrongVideo, AbortUploadRequest(upload.uploadSessionId)) }
        assertEquals(2, service.refreshUploadPartUrls(owner, upload.videoId, RefreshPartsRequest(upload.uploadSessionId, listOf(2, 1))).refreshedParts.size)
    }
    @Test fun concurrentCompletionQueuesOneJobAndSortsParts() = runBlocking {
        val upload = initiate()
        val results = List(3) { async(Dispatchers.IO) { service.completeUpload(owner, upload.videoId, CompleteUploadRequest(upload.uploadSessionId, tags())) } }.awaitAll()
        assertEquals(1, results.map { it.jobId }.distinct().size)
        assertEquals(1, storage.completions)
        assertEquals(listOf(1, 2), storage.completedParts.map { it.partNumber })
        val jobs = DatabaseFactory.dbQuery { ProcessingJobsTable.select { ProcessingJobsTable.videoId eq UUID.fromString(upload.videoId) }.count() }
        assertEquals(1L, jobs)
        rejected { service.abortUpload(owner, upload.videoId, AbortUploadRequest(upload.uploadSessionId)) }
    }
    @Test fun completionRecoversProviderSuccessWithLostResponse() = runBlocking {
        val upload = initiate()
        storage.loseCompletionResponse = true
        try { service.completeUpload(owner, upload.videoId, CompleteUploadRequest(upload.uploadSessionId, tags())); fail() }
        catch (_: java.io.IOException) { }
        val result = service.completeUpload(owner, upload.videoId, CompleteUploadRequest(upload.uploadSessionId, tags()))
        assertEquals("UPLOADED", result.publicationState)
        assertEquals(1, storage.completions)
    }
    @Test fun completionRejectsIncompleteAndIncorrectProviderParts() = runBlocking {
        val upload = initiate()
        rejected { service.completeUpload(owner, upload.videoId, CompleteUploadRequest(upload.uploadSessionId, tags().take(1))) }
        storage.wrongSize = true
        rejected { service.completeUpload(owner, upload.videoId, CompleteUploadRequest(upload.uploadSessionId, tags())) }
        assertEquals(0, storage.completions)
        storage.wrongSize = false
        rejected { service.completeUpload(owner, upload.videoId, CompleteUploadRequest(upload.uploadSessionId, listOf(PartTagRequest(1, "wrong"), PartTagRequest(2, "wrong")))) }
    }
    @Test fun abortIsIdempotentAndBlocksRefreshAndCompletion() = runBlocking {
        val upload = initiate()
        repeat(2) { service.abortUpload(owner, upload.videoId, AbortUploadRequest(upload.uploadSessionId)) }
        assertEquals(1, storage.aborts)
        assertEquals("CANCELLED", service.getUploadStatus(owner, upload.videoId).publicationState)
        rejected { service.refreshUploadPartUrls(owner, upload.videoId, RefreshPartsRequest(upload.uploadSessionId, listOf(1))) }
        rejected { service.completeUpload(owner, upload.videoId, CompleteUploadRequest(upload.uploadSessionId, tags())) }
    }
    @Test fun expiryCleanupRetriesProviderFailure() = runBlocking {
        val upload = initiate()
        DatabaseFactory.dbQuery { UploadSessionsTable.update({ UploadSessionsTable.id eq UUID.fromString(upload.uploadSessionId) }) { it[expiresAt] = Instant.now().minusSeconds(1) } }
        rejected { service.refreshUploadPartUrls(owner, upload.videoId, RefreshPartsRequest(upload.uploadSessionId, listOf(1))) }
        storage.failAbort = true
        service.cleanupExpiredSessions()
        assertEquals("UPLOADING", service.getUploadStatus(owner, upload.videoId).publicationState)
        storage.failAbort = false
        service.cleanupExpiredSessions()
        assertEquals("CANCELLED", service.getUploadStatus(owner, upload.videoId).publicationState)
    }
    private class FakeStorage(config: MapApplicationConfig) : S3StorageService(config) {
        var completions = 0; var aborts = 0
        var loseCompletionResponse = false; var wrongSize = false; var failAbort = false
        var completedParts = emptyList<CompletedPartDto>()
        private val completedKeys = mutableSetOf<String>()
        override fun initiateMultipartUpload(objectKey: String, contentType: String) = UUID.randomUUID().toString()
        override fun generatePresignedPartUploadUrl(objectKey: String, uploadId: String, partNumber: Int) = "https://test.invalid/$partNumber"
        override fun objectHasSize(objectKey: String, bytes: Long) = objectKey in completedKeys
        override fun listUploadedParts(objectKey: String, uploadId: String) = listOf(StoredPart(1, "\"part-1\"", UploadValidation.CHUNK_BYTES.toLong()), StoredPart(2, "\"part-2\"", if (wrongSize) 8 else 7))
        override fun completeMultipartUpload(objectKey: String, uploadId: String, completedParts: List<CompletedPartDto>): Boolean {
            completions++; this.completedParts = completedParts; completedKeys += objectKey
            if (loseCompletionResponse) { loseCompletionResponse = false; throw java.io.IOException("Provider response lost") }
            return true
        }
        override fun abortMultipartUpload(objectKey: String, uploadId: String): Boolean { if (failAbort) throw java.io.IOException("Provider unavailable"); aborts++; return true }
        override fun close() = Unit
    }
}
