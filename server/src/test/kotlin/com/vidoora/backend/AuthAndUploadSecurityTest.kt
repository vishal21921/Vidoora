package com.vidoora.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class AuthAndUploadSecurityTest {

    @Test
    fun testHealthEndpoint() = testApplication {
        application {
            module()
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\": \"UP\""))
    }

    @Test
    fun testFullAuthAndUploadSecurityWorkflow() = testApplication {
        application {
            module()
        }

        // 1. Successful Registration
        val regResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "username": "alice_creator",
                    "email": "alice@vidoora.app",
                    "password": "SecurePassword123!",
                    "displayName": "Alice Creator"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, regResponse.status)
        val regBody = regResponse.bodyAsText()
        assertTrue(regBody.contains("accessToken"))

        // Extract Alice's Token
        val tokenRegex = """"accessToken":\s*"([^"]+)"""".toRegex()
        val aliceToken = tokenRegex.find(regBody)?.groupValues?.get(1)!!

        // 2. Duplicate Registration Rejection
        val dupRegResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "username": "alice_creator",
                    "email": "alice@vidoora.app",
                    "password": "SecurePassword123!",
                    "displayName": "Alice Creator"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.BadRequest, dupRegResponse.status)
        assertTrue(dupRegResponse.bodyAsText().contains("already registered"))

        // 3. Invalid Credentials Login Rejection
        val badLoginResponse = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "email": "alice@vidoora.app",
                    "password": "WrongPassword!"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.BadRequest, badLoginResponse.status)

        // 4. Unauthenticated Access Protection
        val unauthResponse = client.get("/api/v1/users/me")
        assertEquals(HttpStatusCode.Unauthorized, unauthResponse.status)

        // 5. Malformed Token Protection
        val malformedTokenResponse = client.get("/api/v1/users/me") {
            header(HttpHeaders.Authorization, "Bearer malformed.jwt.token")
        }
        assertEquals(HttpStatusCode.Unauthorized, malformedTokenResponse.status)

        // 6. Register User B (Bob)
        val bobRegResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "username": "bob_creator",
                    "email": "bob@vidoora.app",
                    "password": "SecurePassword123!",
                    "displayName": "Bob Creator"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, bobRegResponse.status)
        val bobToken = tokenRegex.find(bobRegResponse.bodyAsText())?.groupValues?.get(1)!!

        // 7. File Size Exceeding 1 GB Rejection Validation
        val oversizedUploadResponse = client.post("/api/v1/videos/initiate-upload") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "title": "Oversized 2GB Video",
                    "description": "Too large",
                    "fileSizeBytes": 2147483648,
                    "mimeType": "video/mp4"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.BadRequest, oversizedUploadResponse.status)
        assertTrue(oversizedUploadResponse.bodyAsText().contains("1 GB"))

        // 8. Unsupported MIME Type Rejection Validation
        val badMimeUploadResponse = client.post("/api/v1/videos/initiate-upload") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "title": "Executable Payload",
                    "description": "Malicious file",
                    "fileSizeBytes": 1048576,
                    "mimeType": "application/x-msdownload"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.BadRequest, badMimeUploadResponse.status)
        assertTrue(badMimeUploadResponse.bodyAsText().contains("Unsupported video MIME type"))

        // Registration alone must never allocate R2 multipart storage.
        val viewerDenied=client.post("/api/v1/videos/initiate-upload") {
            header(HttpHeaders.Authorization,"Bearer $aliceToken");contentType(ContentType.Application.Json)
            setBody("""{"title":"viewer denied","fileSizeBytes":1048576,"mimeType":"video/mp4"}""")
        }
        assertEquals(HttpStatusCode.Forbidden,viewerDenied.status)
        // Trusted test fixture grants creator access; public requests cannot set roles.
        com.vidoora.backend.infrastructure.DatabaseFactory.dbQuery {
            com.vidoora.backend.modules.user.UsersTable.update({com.vidoora.backend.modules.user.UsersTable.email eq "alice@vidoora.app"}) {
                it[com.vidoora.backend.modules.user.UsersTable.role]="CREATOR"
            }
        }
        // 9. Valid Upload Session Initiation by Alice
        val validUploadResponse = client.post("/api/v1/videos/initiate-upload") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "title": "Alice Vidoora Reel",
                    "description": "First valid test upload",
                    "fileSizeBytes": 5242880,
                    "mimeType": "video/mp4"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, validUploadResponse.status)
        val uploadResponseBody = validUploadResponse.bodyAsText()

        val videoIdRegex = """"videoId":\s*"([^"]+)"""".toRegex()
        val sessionRegex = """"uploadSessionId":\s*"([^"]+)"""".toRegex()
        val videoId = videoIdRegex.find(uploadResponseBody)?.groupValues?.get(1)!!
        val sessionId = sessionRegex.find(uploadResponseBody)?.groupValues?.get(1)!!

        // 10. Refresh Part URLs for Active Upload Session
        val refreshResponse = client.post("/api/v1/videos/$videoId/upload-parts/refresh") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "uploadSessionId": "$sessionId",
                    "partNumbers": [1]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, refreshResponse.status)
        assertTrue(refreshResponse.bodyAsText().contains("refreshedParts"))

        // 11. Security Ownership Check: Bob attempts to refresh Alice's parts
        val unauthRefreshResponse = client.post("/api/v1/videos/$videoId/upload-parts/refresh") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "uploadSessionId": "$sessionId",
                    "partNumbers": [1]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Forbidden, unauthRefreshResponse.status)

        // 12. Security Ownership Check: Bob attempts to complete Alice's upload session
        val unauthorizedCompleteResponse = client.post("/api/v1/videos/$videoId/complete-upload") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "uploadSessionId": "$sessionId",
                    "parts": [
                        { "partNumber": 1, "eTag": "\"etag-1\"" }
                    ]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Forbidden, unauthorizedCompleteResponse.status)
        assertTrue(unauthorizedCompleteResponse.bodyAsText().contains("not authorized"))

        // 13. Alice Completes Upload Session Successfully
        val aliceCompleteResponse = client.post("/api/v1/videos/$videoId/complete-upload") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "uploadSessionId": "$sessionId",
                    "parts": [
                        { "partNumber": 1, "eTag": "\"etag-1\"" }
                    ]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, aliceCompleteResponse.status)
        assertTrue(aliceCompleteResponse.bodyAsText().contains("UPLOADED"))

        // 14. Idempotency Check: Alice Calls Completion Again
        val idempotentResponse = client.post("/api/v1/videos/$videoId/complete-upload") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "uploadSessionId": "$sessionId",
                    "parts": [
                        { "partNumber": 1, "eTag": "\"etag-1\"" }
                    ]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, idempotentResponse.status)
        assertTrue(idempotentResponse.bodyAsText().contains("idempotently"))

        // 15. Initiate Second Upload & Test Abort Route
        val upload2Response = client.post("/api/v1/videos/initiate-upload") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "title": "Video To Abort",
                    "fileSizeBytes": 10485760,
                    "mimeType": "video/mp4"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, upload2Response.status)
        val upload2Body = upload2Response.bodyAsText()
        val videoId2 = videoIdRegex.find(upload2Body)?.groupValues?.get(1)!!
        val sessionId2 = sessionRegex.find(upload2Body)?.groupValues?.get(1)!!

        val abortResponse = client.post("/api/v1/videos/$videoId2/abort-upload") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "uploadSessionId": "$sessionId2"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, abortResponse.status)
        assertTrue(abortResponse.bodyAsText().contains("CANCELLED"))
    }
}

