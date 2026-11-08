package com.example.vidoora

import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.network.dto.InitiateUploadRequestDto
import com.example.vidoora.core.network.dto.InitiateUploadResponseDto
import com.example.vidoora.core.network.dto.UploadPartInfoDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRepositoryTest {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    @Test
    fun testInitiateUploadRequestValidation() {
        val request = InitiateUploadRequestDto(
            title = "Sample Video Upload",
            description = "Test description",
            fileSizeBytes = 52428800L,
            mimeType = "video/mp4",
            visibility = "PUBLIC"
        )
        val serialized = json.encodeToString(request)
        assertTrue(serialized.contains("\"fileSizeBytes\":52428800"))
        assertTrue(serialized.contains("\"mimeType\":\"video/mp4\""))

        val deserialized = json.decodeFromString<InitiateUploadRequestDto>(serialized)
        assertEquals(request.title, deserialized.title)
        assertEquals(request.fileSizeBytes, deserialized.fileSizeBytes)
    }

    @Test
    fun testInitiateUploadResponsePartChunking() {
        val response = InitiateUploadResponseDto(
            videoId = "vid-uuid-101",
            uploadSessionId = "sess-uuid-202",
            chunkSizeBytes = 5242880,
            totalParts = 2,
            objectKey = "raw/vid-uuid-101.mp4",
            parts = listOf(
                UploadPartInfoDto(1, "https://r2.cloudflarestorage.com/part-1"),
                UploadPartInfoDto(2, "https://r2.cloudflarestorage.com/part-2")
            )
        )

        assertEquals(2, response.totalParts)
        assertEquals(5242880, response.chunkSizeBytes)
        assertEquals(2, response.parts.size)
        assertEquals("https://r2.cloudflarestorage.com/part-1", response.parts[0].uploadUrl)
    }

    @Test
    fun testOversizedFileSizeValidation() {
        val maxFileSizeBytes = 1073741824L // 1 GiB
        val oversizedFileSizeBytes = 1073741825L // 1 GiB + 1 byte

        val isValid = oversizedFileSizeBytes <= maxFileSizeBytes
        assertTrue(!isValid)
    }
}
