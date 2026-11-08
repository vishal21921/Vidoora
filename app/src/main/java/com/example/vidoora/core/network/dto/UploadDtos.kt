package com.example.vidoora.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class InitiateUploadRequestDto(
    val title: String,
    val description: String? = null,
    val fileSizeBytes: Long,
    val mimeType: String,
    val visibility: String = "PRIVATE"
)

@Serializable
data class UploadPartInfoDto(
    val partNumber: Int,
    val uploadUrl: String
)

@Serializable
data class InitiateUploadResponseDto(
    val videoId: String,
    val uploadSessionId: String,
    val chunkSizeBytes: Int,
    val totalParts: Int,
    val objectKey: String,
    val parts: List<UploadPartInfoDto>
)

@Serializable
data class PartTagDto(
    val partNumber: Int,
    val eTag: String
)

@Serializable
data class CompleteUploadRequestDto(
    val uploadSessionId: String,
    val parts: List<PartTagDto>
)

@Serializable
data class CompleteUploadResponseDto(
    val videoId: String,
    val publicationState: String,
    val jobId: String,
    val message: String
)

@Serializable
data class RefreshPartsRequestDto(
    val uploadSessionId: String,
    val partNumbers: List<Int>
)

@Serializable
data class RefreshPartsResponseDto(
    val videoId: String,
    val uploadSessionId: String,
    val refreshedParts: List<UploadPartInfoDto>
)

@Serializable
data class AbortUploadRequestDto(
    val uploadSessionId: String
)

@Serializable
data class AbortUploadResponseDto(
    val videoId: String,
    val publicationState: String,
    val message: String
)

@Serializable
data class UploadStatusResponseDto(
    val videoId: String,
    val publicationState: String,
    val processingStatus: String,
    val errorMessage: String? = null,
    val masterPlaylistUrl: String? = null
)

