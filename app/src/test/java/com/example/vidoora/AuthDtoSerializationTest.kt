package com.example.vidoora

import com.example.vidoora.core.network.dto.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthDtoSerializationTest {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    @Test
    fun testRegisterRequestSerialization() {
        val request = RegisterRequestDto(
            username = "alice_creator",
            email = "alice@vidoora.app",
            password = "Password123!",
            displayName = "Alice Creator"
        )
        val serialized = json.encodeToString(request)
        assertTrue(serialized.contains("\"username\":\"alice_creator\""))
        assertTrue(serialized.contains("\"email\":\"alice@vidoora.app\""))

        val deserialized = json.decodeFromString<RegisterRequestDto>(serialized)
        assertEquals(request.username, deserialized.username)
        assertEquals(request.email, deserialized.email)
    }

    @Test
    fun testApiEnvelopeSerialization() {
        val user = UserDto(
            id = "user-uuid-1",
            username = "alice",
            email = "alice@vidoora.app",
            displayName = "Alice",
            createdAt = "2026-10-12T10:00:00Z"
        )
        val envelope = ApiEnvelope(
            success = true,
            data = user,
            error = null
        )
        val serialized = json.encodeToString(envelope)
        assertTrue(serialized.contains("\"success\":true"))
        assertTrue(serialized.contains("\"username\":\"alice\""))

        val deserialized = json.decodeFromString<ApiEnvelope<UserDto>>(serialized)
        assertTrue(deserialized.success)
        assertEquals("alice", deserialized.data?.username)
    }
}
