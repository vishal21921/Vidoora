package com.vidoora.backend.infrastructure

import io.ktor.server.config.*
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.nio.file.Files
import java.nio.file.Path
import software.amazon.awssdk.core.sync.RequestBody

open class S3StorageService(config: ApplicationConfig) : AutoCloseable {
    val rawBucketName: String = config.propertyOrNull("r2.rawBucketName")?.getString() ?: "vidoora-raw-uploads"
    val processedBucketName: String = config.propertyOrNull("r2.processedBucketName")?.getString() ?: "vidoora-processed-hls"
    private val endpoint: String = config.propertyOrNull("r2.endpoint")?.getString() ?: "https://mock-r2.cloudflarestorage.com"
    private val accessKeyId: String = config.propertyOrNull("r2.accessKeyId")?.getString() ?: "mock-key"
    private val secretAccessKey: String = config.propertyOrNull("r2.secretAccessKey")?.getString() ?: "mock-secret"

    val isMockMode: Boolean = config.propertyOrNull("r2.mockMode")?.getString()?.toBoolean() == true

    init {
        require(isMockMode || (accessKeyId !in setOf("mock-r2-access-key", "mock-key") && secretAccessKey !in setOf("mock-secret", "mock-r2-secret-key"))) {
            "Configure R2 credentials or explicitly enable development mock mode"
        }
    }

    private val s3Client: S3Client by lazy {
        S3Client.builder()
            .overrideConfiguration { it.apiCallTimeout(Duration.ofMinutes(2)).apiCallAttemptTimeout(Duration.ofSeconds(60)) }
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
            .build()
    }

    private val s3Presigner: S3Presigner by lazy {
        S3Presigner.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
            .build()
    }

    open fun initiateMultipartUpload(objectKey: String, contentType: String): String {
        if (isMockMode) {
            return "mock-upload-id-" + UUID.randomUUID().toString().take(8)
        }
        val request = CreateMultipartUploadRequest.builder()
            .bucket(rawBucketName)
            .key(objectKey)
            .contentType(contentType)
            .build()
        val response = s3Client.createMultipartUpload(request)
        return response.uploadId()
    }

    open fun generatePresignedPartUploadUrl(objectKey: String, uploadId: String, partNumber: Int): String {
        if (isMockMode) {
            return "$endpoint/$rawBucketName/$objectKey?uploadId=$uploadId&partNumber=$partNumber&mockSig=valid"
        }
        val uploadPartRequest = UploadPartRequest.builder()
            .bucket(rawBucketName)
            .key(objectKey)
            .uploadId(uploadId)
            .partNumber(partNumber)
            .build()

        val presignRequest = UploadPartPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1)) // Short 1-hour presigned URL duration
            .uploadPartRequest(uploadPartRequest)
            .build()

        return s3Presigner.presignUploadPart(presignRequest).url().toString()
    }

    open fun completeMultipartUpload(objectKey: String, uploadId: String, completedParts: List<CompletedPartDto>): Boolean {
        if (isMockMode) {
            return true
        }
        val parts = completedParts.sortedBy { it.partNumber }.map {
            CompletedPart.builder()
                .partNumber(it.partNumber)
                .eTag(it.eTag)
                .build()
        }
        val completedUpload = CompletedMultipartUpload.builder().parts(parts).build()
        val request = CompleteMultipartUploadRequest.builder()
            .bucket(rawBucketName)
            .key(objectKey)
            .uploadId(uploadId)
            .multipartUpload(completedUpload)
            .build()

        val response = s3Client.completeMultipartUpload(request)
        return response.eTag() != null
    }

    open fun abortMultipartUpload(objectKey: String, uploadId: String): Boolean {
        if (isMockMode) {
            return true
        }
        val request = AbortMultipartUploadRequest.builder()
            .bucket(rawBucketName)
            .key(objectKey)
            .uploadId(uploadId)
            .build()
        try {
            s3Client.abortMultipartUpload(request)
        } catch (e: S3Exception) {
            if (e.awsErrorDetails()?.errorCode() != "NoSuchUpload") throw e
        }
        return true
    }

    open fun listUploadedParts(objectKey: String, uploadId: String): List<StoredPart> {
        if (isMockMode) return emptyList()
        return s3Client.listPartsPaginator(ListPartsRequest.builder()
            .bucket(rawBucketName).key(objectKey).uploadId(uploadId).build())
            .flatMap { page -> page.parts().map { StoredPart(it.partNumber(), it.eTag(), it.size()) } }
    }

    open fun objectHasSize(objectKey: String, bytes: Long): Boolean {
        if (isMockMode) return false
        return try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(rawBucketName).key(objectKey).build())
                .contentLength() == bytes
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) false else throw e
        }
    }

    open fun downloadOriginal(objectKey: String, destination: Path, expectedBytes: Long) {
        check(!isMockMode) { "Processing requires real storage" }
        require(expectedBytes in 1..1_073_741_824L)
        s3Client.getObject(GetObjectRequest.builder().bucket(rawBucketName).key(objectKey).build()).use { input ->
            check(input.response().contentLength() == expectedBytes) { "Original size mismatch" }
            Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= expectedBytes) { "Original size exceeded" }
                    out.write(buffer,0,count)
                }
                check(total == expectedBytes) { "Incomplete original" }
            }
        }
    }
    open fun uploadProcessed(bucket: String, key: String, file: Path) {
        check(!isMockMode)
        require(key.startsWith("processed/") && !key.contains(".."))
        val type = when { key.endsWith(".jpg") -> "image/jpeg"; key.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"; else -> "video/mp2t" }
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).build(), RequestBody.fromFile(file))
        check(s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength()==Files.size(file)) { "Output size mismatch" }
    }
    open fun uploadThumbnail(bucket: String,key: String,bytes: ByteArray) {
        check(!isMockMode)
        require(bucket==rawBucketName || bucket==processedBucketName)
        require(Regex("processed/[0-9a-f-]{36}/[0-9a-f-]{36}/custom-thumbnail\\.jpg").matches(key))
        require(bytes.size in 4..256*1024)
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/jpeg").cacheControl("no-store").build(),RequestBody.fromBytes(bytes))
        check(s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength()==bytes.size.toLong())
    }
    open fun deleteVideoObjects(videoId: UUID,rawKey: String?,buckets: List<String>,alive: ()->Boolean) {
        check(!isMockMode)
        com.vidoora.backend.modules.deletion.DeletionPaths.checkRaw(videoId,rawKey)
        require(buckets.all { it==rawBucketName || it==processedBucketName }) { "Unconfigured deletion bucket" }
        val prefix="processed/$videoId/" // Exact video boundary, never an account/bucket-wide prefix.
        for(bucket in buckets.distinct()) {
            for(page in s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())) {
                for(item in page.contents()) {
                    check(alive()); require(item.key().startsWith(prefix))
                    s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(item.key()).build())
                }
            }
            check(alive())
            check(s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(1).build()).contents().isEmpty()) { "Video assets remain" }
        }
        if(rawKey!=null) {
            check(alive())
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(rawBucketName).key(rawKey).build())
            try {
                s3Client.headObject(HeadObjectRequest.builder().bucket(rawBucketName).key(rawKey).build())
                error("Original remains")
            } catch(e: S3Exception) { if(e.statusCode()!=404) throw e }
        }
    }
    open fun deleteProcessingPrefix(bucket: String, prefix: String) {
        check(!isMockMode)
        // Only UUID attempt prefixes owned by processing_attempts may be passed by the worker.
        require(Regex("processed/[0-9a-f-]{36}/[0-9a-f-]{36}/").matches(prefix))
        for (page in s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())) {
            for (objectEntry in page.contents()) s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectEntry.key()).build())
        }
    }
    open fun readProcessed(bucket: String, key: String, maxBytes: Int): ByteArray {
        check(!isMockMode) { "Playback requires real storage" }
        require(bucket == rawBucketName || bucket == processedBucketName)
        require(key.startsWith("processed/") && !key.contains(".."))
        s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()).use { input ->
            val length = input.response().contentLength()
            check(length in 1..maxBytes.toLong()) { "Playback asset exceeds size limit" }
            val result = java.io.ByteArrayOutputStream(length.toInt())
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxBytes && total <= length) { "Playback asset exceeds size limit" }
                result.write(buffer,0,count)
            }
            check(total.toLong() == length) { "Incomplete playback asset" }
            return result.toByteArray()
        }
    }
    override fun close() {
        if (!isMockMode) {
            s3Client.close()
            s3Presigner.close()
        }
    }
}

data class StoredPart(val partNumber: Int, val eTag: String, val sizeBytes: Long)

data class CompletedPartDto(
    val partNumber: Int,
    val eTag: String
)


