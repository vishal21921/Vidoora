package com.vidoora.backend.modules.upload

import com.vidoora.backend.infrastructure.CompletedPartDto
import com.vidoora.backend.infrastructure.DatabaseFactory.dbQuery
import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.user.UsersTable
import com.vidoora.backend.modules.video.ProcessingJobsTable
import com.vidoora.backend.modules.video.UploadSessionsTable
import com.vidoora.backend.modules.video.VideosTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

class UploadService(private val storageService: S3StorageService) {
    private fun uuid(value: String): UUID = try { UUID.fromString(value) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid ID format") }

    private fun checkUser(userId: String) {
        val user = UsersTable.select { UsersTable.id eq uuid(userId) }.firstOrNull()
            ?: throw IllegalStateException("Account not found")
        check(!user[UsersTable.isSuspended]) { "Account is suspended" }
    }

    // Row lock serializes completion, refresh and cancellation across backend instances.
    private fun ownedSession(userId: String, videoId: String, sessionId: String): ResultRow {
        checkUser(userId)
        val row = UploadSessionsTable.select { UploadSessionsTable.id eq uuid(sessionId) }
            .forUpdate().firstOrNull() ?: throw IllegalArgumentException("Upload session not found")
        check(row[UploadSessionsTable.uploaderId] == uuid(userId)) { "User is not authorized to modify this upload session" }
        require(row[UploadSessionsTable.videoId] == uuid(videoId)) { "Upload session does not belong to this video" }
        check(VideosTable.select { VideosTable.id eq row[UploadSessionsTable.videoId] }.single()[VideosTable.deletedAt]==null) { "Video was deleted" }
        return row
    }

    private fun requireActive(row: ResultRow) {
        check(row[UploadSessionsTable.status] == "ACTIVE") { "Upload session is no longer active" }
        check(row[UploadSessionsTable.expiresAt].isAfter(Instant.now())) { "Upload session expired" }
    }

    // Keep the creator role stable through issuance or completion of an upload.
    private fun lockCreator(userId: String) {
        val user = UsersTable.select { UsersTable.id eq uuid(userId) }.forUpdate().firstOrNull()
        check(user != null && !user[UsersTable.isSuspended] && user[UsersTable.role] in setOf("CREATOR", "ADMIN")) {
            "A creator invitation is required to upload"
        }
    }

    suspend fun initiateUpload(uploaderIdStr: String, request: InitiateUploadRequest): InitiateUploadResponse {
        val userId = uuid(uploaderIdStr)
        val title = request.title.trim()
        require(title.isNotEmpty() && title.length <= 128) { "Video title must be between 1 and 128 characters" }
        require(request.fileSizeBytes in 1..UploadValidation.MAX_BYTES) { "File size must be greater than 0 and cannot exceed 1 GB (1,073,741,824 bytes)" }
        val mime = request.mimeType.trim().lowercase()
        val extension = mapOf("video/mp4" to "mp4", "video/quicktime" to "mov", "video/x-matroska" to "mkv", "video/webm" to "webm")[mime]
            ?: throw IllegalArgumentException("Unsupported video MIME type")
        val visibility = request.visibility.trim().uppercase()
        require(visibility in setOf("UNLISTED", "PRIVATE")) { "Public uploads are not supported; update the app and upload privately" }
        dbQuery {
            checkUser(uploaderIdStr)
            val user=UsersTable.select {UsersTable.id eq userId}.single()
            check(user[UsersTable.role] in setOf("CREATOR","ADMIN")) { "A creator invitation is required to upload" }
        }
        val videoId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val key = "raw/$videoId.$extension"
        val total = UploadValidation.partCount(request.fileSizeBytes)
        // Storage calls execute on the database IO dispatcher, never Netty's event loop.
        return dbQuery {
            val creator=UsersTable.select {UsersTable.id eq userId}.forUpdate().single()
            check(!creator[UsersTable.isSuspended] && creator[UsersTable.role] in setOf("CREATOR","ADMIN")) {"A creator invitation is required to upload"}
            val providerId = storageService.initiateMultipartUpload(key, mime)
            try {
                val parts = (1..total).map { UploadPartInfo(it, storageService.generatePresignedPartUploadUrl(key, providerId, it)) }
                VideosTable.insert {
                    it[id] = videoId; it[uploaderId] = userId; it[this.title] = title
                    it[description] = request.description?.trim(); it[publicationState] = "UPLOADING"
                    it[this.visibility] = visibility; it[rawObjectKey] = key
                    it[createdAt] = Instant.now(); it[updatedAt] = Instant.now()
                }
                UploadSessionsTable.insert {
                    it[id] = sessionId; it[this.videoId] = videoId; it[uploaderId] = userId
                    it[uploadIdProvider] = providerId; it[objectKey] = key; it[totalBytes] = request.fileSizeBytes
                    it[chunkSizeByes] = UploadValidation.CHUNK_BYTES; it[status] = "ACTIVE"
                    it[expiresAt] = Instant.now().plusSeconds(86400); it[createdAt] = Instant.now()
                }
                InitiateUploadResponse(videoId.toString(), sessionId.toString(), UploadValidation.CHUNK_BYTES, total, key, parts)
            } catch (e: Exception) {
                try { storageService.abortMultipartUpload(key, providerId) } catch (_: Exception) { /* bucket lifecycle is the final safety net */ }
                throw e
            }
        }
    }

    suspend fun completeUpload(uploaderIdStr: String, videoIdStr: String, request: CompleteUploadRequest): CompleteUploadResponse = dbQuery {
        lockCreator(uploaderIdStr)
        val row = ownedSession(uploaderIdStr, videoIdStr, request.uploadSessionId)
        val videoId = row[UploadSessionsTable.videoId]
        if (row[UploadSessionsTable.status] == "COMPLETED") {
            // Repair the old implementation's crash gap between marking completion
            // and inserting its processing job, without deleting historical jobs.
            val job = ProcessingJobsTable.select { ProcessingJobsTable.videoId eq videoId }.firstOrNull()
            val jobId = job?.get(ProcessingJobsTable.id) ?: UUID.randomUUID().also { newId ->
                ProcessingJobsTable.insert { it[id] = newId; it[this.videoId] = videoId; it[createdAt] = Instant.now() }
            }
            return@dbQuery CompleteUploadResponse(videoIdStr, "UPLOADED", jobId.toString(), "Upload was already completed idempotently.")
        }
        requireActive(row)
        val bytes = row[UploadSessionsTable.totalBytes]
        val chunk = row[UploadSessionsTable.chunkSizeByes]
        val total = UploadValidation.partCount(bytes, chunk)
        val parts = UploadValidation.orderedParts(request.parts, total)
        val key = row[UploadSessionsTable.objectKey]
        val providerId = row[UploadSessionsTable.uploadIdProvider]
        // A successful provider completion followed by a lost response/DB rollback is recoverable.
        if (!storageService.objectHasSize(key, bytes)) {
            if (!storageService.isMockMode) {
                val stored = storageService.listUploadedParts(key, providerId).associateBy { it.partNumber }
                require(stored.size == total) { "Storage does not contain every part" }
                for (part in parts) {
                    val actual = stored[part.partNumber] ?: throw IllegalArgumentException("Missing stored part")
                    val expected = minOf(chunk.toLong(), bytes - (part.partNumber - 1L) * chunk)
                    require(actual.sizeBytes == expected && actual.eTag.trim().removeSurrounding("\"") == part.eTag.removeSurrounding("\"")) {
                        "Stored part size or ETag does not match completion request"
                    }
                }
            }
            check(storageService.completeMultipartUpload(key, providerId, parts.map { CompletedPartDto(it.partNumber, it.eTag) })) { "Storage completion failed" }
            check(storageService.isMockMode || storageService.objectHasSize(key, bytes)) { "Completed object size does not match upload" }
        }
        UploadSessionsTable.update({ UploadSessionsTable.id eq row[UploadSessionsTable.id] }) { it[status] = "COMPLETED" }
        VideosTable.update({ VideosTable.id eq videoId }) { it[publicationState] = "UPLOADED"; it[updatedAt] = Instant.now() }
        val jobId = UUID.randomUUID()
        ProcessingJobsTable.insert { it[id] = jobId; it[this.videoId] = videoId; it[createdAt] = Instant.now() }
        CompleteUploadResponse(videoIdStr, "UPLOADED", jobId.toString(), "Upload completed successfully. Video queued for processing.")
    }

    suspend fun refreshUploadPartUrls(uploaderIdStr: String, videoIdStr: String, request: RefreshPartsRequest): RefreshPartsResponse = dbQuery {
        lockCreator(uploaderIdStr)
        val row = ownedSession(uploaderIdStr, videoIdStr, request.uploadSessionId)
        requireActive(row)
        UploadValidation.validatePartNumbers(request.partNumbers, UploadValidation.partCount(row[UploadSessionsTable.totalBytes], row[UploadSessionsTable.chunkSizeByes]))
        val parts = request.partNumbers.sorted().map { UploadPartInfo(it, storageService.generatePresignedPartUploadUrl(row[UploadSessionsTable.objectKey], row[UploadSessionsTable.uploadIdProvider], it)) }
        RefreshPartsResponse(videoIdStr, request.uploadSessionId, parts)
    }

    suspend fun abortUpload(uploaderIdStr: String, videoIdStr: String, request: AbortUploadRequest): AbortUploadResponse = dbQuery {
        val row = ownedSession(uploaderIdStr, videoIdStr, request.uploadSessionId)
        check(row[UploadSessionsTable.status] != "COMPLETED") { "Completed upload cannot be cancelled" }
        if (row[UploadSessionsTable.status] != "ABORTED") {
            check(!storageService.objectHasSize(row[UploadSessionsTable.objectKey], row[UploadSessionsTable.totalBytes])) { "Upload already completed at storage; retry completion" }
            check(storageService.abortMultipartUpload(row[UploadSessionsTable.objectKey], row[UploadSessionsTable.uploadIdProvider])) { "Storage cancellation failed" }
            UploadSessionsTable.update({ UploadSessionsTable.id eq row[UploadSessionsTable.id] }) { it[status] = "ABORTED" }
            VideosTable.update({ VideosTable.id eq row[UploadSessionsTable.videoId] }) { it[publicationState] = "CANCELLED"; it[updatedAt] = Instant.now() }
        }
        AbortUploadResponse(videoIdStr, "CANCELLED", "Upload session aborted and cancelled successfully.")
    }

    suspend fun getUploadStatus(uploaderIdStr: String, videoIdStr: String): UploadStatusResponse = dbQuery {
        checkUser(uploaderIdStr)
        val videoId = uuid(videoIdStr)
        val row = VideosTable.select { VideosTable.id eq videoId }.firstOrNull() ?: throw IllegalArgumentException("Video not found")
        check(row[VideosTable.uploaderId] == uuid(uploaderIdStr)) { "User is not authorized to view upload status" }
        val job = ProcessingJobsTable.select { ProcessingJobsTable.videoId eq videoId }.firstOrNull()
        UploadStatusResponse(videoIdStr, row[VideosTable.publicationState], job?.get(ProcessingJobsTable.status) ?: "NOT_QUEUED", job?.get(ProcessingJobsTable.errorMessage), row[VideosTable.masterPlaylistUrl])
    }

    // Cleanup must also run for suspended/deleted-login accounts, without a user token.
    suspend fun cleanupExpiredSessions() {
        val sessions = dbQuery { UploadSessionsTable.select { (UploadSessionsTable.status eq "ACTIVE") and (UploadSessionsTable.expiresAt less Instant.now()) }.map { Triple(it[UploadSessionsTable.uploaderId].toString(), it[UploadSessionsTable.videoId].toString(), it[UploadSessionsTable.id].toString()) } }
        for ((_, _, session) in sessions) {
            try { dbQuery {
                val row = UploadSessionsTable.select { UploadSessionsTable.id eq uuid(session) }.forUpdate().firstOrNull() ?: return@dbQuery
                if (row[UploadSessionsTable.status] != "ACTIVE" || row[UploadSessionsTable.expiresAt].isAfter(Instant.now())) return@dbQuery
                val videoId = row[UploadSessionsTable.videoId]
                val completed = storageService.objectHasSize(row[UploadSessionsTable.objectKey], row[UploadSessionsTable.totalBytes])
                if (!completed) check(storageService.abortMultipartUpload(row[UploadSessionsTable.objectKey], row[UploadSessionsTable.uploadIdProvider]))
                UploadSessionsTable.update({ UploadSessionsTable.id eq row[UploadSessionsTable.id] }) { it[status] = if (completed) "COMPLETED" else "ABORTED" }
                VideosTable.update({ VideosTable.id eq videoId }) { it[publicationState] = if (completed) "UPLOADED" else "CANCELLED"; it[updatedAt] = Instant.now() }
                if (completed) ProcessingJobsTable.insert { it[id] = UUID.randomUUID(); it[this.videoId] = videoId; it[createdAt] = Instant.now() }
            } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* retried next sweep; bucket lifecycle covers orphaned uploads */ }
        }
    }
}
@Serializable
data class InitiateUploadRequest(
    val title: String,
    val description: String? = null,
    val fileSizeBytes: Long,
    val mimeType: String,
    val visibility: String = "PRIVATE"
)

@Serializable
data class UploadPartInfo(
    val partNumber: Int,
    val uploadUrl: String
)

@Serializable
data class InitiateUploadResponse(
    val videoId: String,
    val uploadSessionId: String,
    val chunkSizeBytes: Int,
    val totalParts: Int,
    val objectKey: String,
    val parts: List<UploadPartInfo>
)

@Serializable
data class PartTagRequest(
    val partNumber: Int,
    val eTag: String
)

@Serializable
data class CompleteUploadRequest(
    val uploadSessionId: String,
    val parts: List<PartTagRequest>
)

@Serializable
data class CompleteUploadResponse(
    val videoId: String,
    val publicationState: String,
    val jobId: String,
    val message: String
)

@Serializable
data class RefreshPartsRequest(
    val uploadSessionId: String,
    val partNumbers: List<Int>
)

@Serializable
data class RefreshPartsResponse(
    val videoId: String,
    val uploadSessionId: String,
    val refreshedParts: List<UploadPartInfo>
)

@Serializable
data class AbortUploadRequest(
    val uploadSessionId: String
)

@Serializable
data class AbortUploadResponse(
    val videoId: String,
    val publicationState: String,
    val message: String
)

@Serializable
data class UploadStatusResponse(
    val videoId: String,
    val publicationState: String,
    val processingStatus: String,
    val errorMessage: String? = null,
    val masterPlaylistUrl: String? = null
)



