package com.vidoora.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.junit.Assume.assumeTrue
import org.junit.Test
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/** Opt-in only; all objects are confined to a named, dedicated test bucket. */
class LiveR2IntegrationTest {
    @Test fun backendMultipartRoundTripAndAbort() {
        assumeTrue("Live R2 test was not explicitly enabled", System.getenv("VIDOORA_LIVE_R2_TEST") == "true")
        val bucket = requireNotNull(System.getenv("R2_TEST_BUCKET")) { "Set R2_TEST_BUCKET securely" }
        require(bucket.startsWith("vidoora-test-")) { "Live test requires a dedicated vidoora-test-* bucket" }
        val endpoint = requireNotNull(System.getenv("R2_ENDPOINT"))
        val key = requireNotNull(System.getenv("R2_ACCESS_KEY_ID"))
        val secret = requireNotNull(System.getenv("R2_SECRET_ACCESS_KEY"))
        val s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build()
        val objectKeys = mutableListOf<String>()
        val sessions = mutableListOf<Pair<String, String>>()
        try {
            testApplication {
                environment {
                    config = MapApplicationConfig(
                        "jwt.secret" to UUID.randomUUID().toString(),
                        "database.jdbcUrl" to "jdbc:h2:mem:vidoora_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                        "database.driverClassName" to "org.h2.Driver", "database.username" to "sa", "database.password" to "",
                        "database.allowInMemoryFallback" to "true",
                        "r2.mockMode" to "false", "r2.rawBucketName" to bucket,
                        "r2.endpoint" to endpoint, "r2.accessKeyId" to key, "r2.secretAccessKey" to secret
                    )
                }
                application { module() }
                val suffix = UUID.randomUUID().toString().take(8)
                val registration = client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"live_$suffix","email":"$suffix@example.test","password":"TestPassword123!","displayName":"Live test"}""")
                }
                assertEquals(HttpStatusCode.Created, registration.status)
                val token = Json.parseToJsonElement(registration.bodyAsText()).jsonObject.getValue("data").jsonObject.getValue("accessToken").jsonPrimitive.content
                com.vidoora.backend.infrastructure.DatabaseFactory.dbQuery {
                    com.vidoora.backend.modules.user.UsersTable.update({com.vidoora.backend.modules.user.UsersTable.email eq "$suffix@example.test"}) {
                        it[com.vidoora.backend.modules.user.UsersTable.role]="CREATOR"
                    }
                }

                suspend fun initiate(): JsonObject {
                    val response = client.post("/api/v1/videos/initiate-upload") {
                        bearerAuth(token); contentType(ContentType.Application.Json)
                        setBody("""{"title":"Phase 3.3 integration fixture","fileSizeBytes":5242887,"mimeType":"video/mp4","visibility":"PRIVATE"}""")
                    }
                    assertEquals(HttpStatusCode.Created, response.status)
                    return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonObject.also {
                        val objectKey = it.getValue("objectKey").jsonPrimitive.content
                        objectKeys += objectKey
                        sessions += objectKey to s3.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).prefix(objectKey).build()).uploads().single().uploadId()
                    }
                }
                val fixture = ByteArray(5_242_887) { (it % 251).toByte() }
                val upload = initiate()
                val parts = upload.getValue("parts").jsonArray
                val transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                suspend fun put(url: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
                    val response = transport.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
                    assertEquals(200, response.statusCode())
                    response.headers().firstValue("ETag").orElseThrow()
                }
                val tags = parts.mapIndexed { index, part ->
                    val data = part.jsonObject
                    val start = index * 5_242_880
                    val tag = put(data.getValue("uploadUrl").jsonPrimitive.content, fixture.copyOfRange(start, minOf(start + 5_242_880, fixture.size)))
                    buildJsonObject { put("partNumber", index + 1); put("eTag", tag) }
                }
                val video = upload.getValue("videoId").jsonPrimitive.content
                val completionBody = buildJsonObject { put("uploadSessionId", upload.getValue("uploadSessionId")); put("parts", JsonArray(tags.reversed())) }.toString()
                repeat(2) {
                    val completion = client.post("/api/v1/videos/$video/complete-upload") {
                        bearerAuth(token); contentType(ContentType.Application.Json); setBody(completionBody)
                    }
                    assertEquals(HttpStatusCode.OK, completion.status)
                }
                val returned = withContext(Dispatchers.IO) { s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(upload.getValue("objectKey").jsonPrimitive.content).build()).asByteArray() }
                val digest = MessageDigest.getInstance("SHA-256")
                assertArrayEquals(digest.digest(fixture), digest.digest(returned))
                val aborted = initiate()
                put(aborted.getValue("parts").jsonArray.first().jsonObject.getValue("uploadUrl").jsonPrimitive.content, fixture.copyOfRange(0, 5_242_880))
                val abort = client.post("/api/v1/videos/${aborted.getValue("videoId").jsonPrimitive.content}/abort-upload") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("uploadSessionId", aborted.getValue("uploadSessionId")) }.toString())
                }
                assertEquals(HttpStatusCode.OK, abort.status)
                assertTrue(s3.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).prefix(aborted.getValue("objectKey").jsonPrimitive.content).build()).uploads().isEmpty())
            }
        } finally {
            // These keys were created by this run; never enumerate/delete unrelated bucket objects.
            for ((objectKey, id) in sessions) try { s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(objectKey).uploadId(id).build()) } catch (_: S3Exception) { }
            for (objectKey in objectKeys) s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build())
            s3.close()
        }
    }
}
