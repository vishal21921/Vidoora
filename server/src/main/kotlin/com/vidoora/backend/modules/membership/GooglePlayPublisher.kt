package com.vidoora.backend.modules.membership

import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Server-only credentials. Do not package the JSON key with an APK or keep it inside the project. */
internal class GooglePlayPublisher private constructor(
    private val credentials: com.google.auth.oauth2.GoogleCredentials
) : PlayPublisher {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private val root = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.vidoora.app/purchases"

    override suspend fun subscription(token: String): JsonObject {
        validateToken(token)
        return try { Json.parseToJsonElement(request("$root/subscriptionsv2/tokens/${playPath(token)}", false)).jsonObject }
        catch (e: CancellationException) { throw e }
        catch (e: PlayVerificationFailure) { throw e }
        catch (_: Exception) { throw PlayVerificationFailure("PLAY_RESPONSE_INVALID") }
    }

    override suspend fun acknowledge(productId: String, token: String) {
        validateToken(token)
        request("$root/subscriptions/${playPath(productId)}/tokens/${playPath(token)}:acknowledge", true)
    }

    private suspend fun request(url: String, post: Boolean): String = withContext(Dispatchers.IO) {
        try {
            val bearer = synchronized(credentials) {
                credentials.refreshIfExpired()
                checkNotNull(credentials.accessToken?.tokenValue)
            }
            val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $bearer").header("Accept", "application/json")
            if (post) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
            else builder.GET()
            // Bound both total body allocation and total response time, including a stalled body.
            val future = client.sendAsync(builder.build(), HttpResponse.BodyHandler { LimitedPlayBody() })
            val response = withTimeout(25_000) {
                suspendCancellableCoroutine<HttpResponse<ByteArray>> { continuation ->
                    continuation.invokeOnCancellation { future.cancel(true) }
                    future.whenComplete { response, error ->
                        if (error != null) continuation.resumeWithException(error)
                        else continuation.resume(response)
                    }
                }
            }
            if (response.statusCode() !in 200..299) {
                throw PlayVerificationFailure(if (response.statusCode() in listOf(404, 410)) "PURCHASE_NOT_FOUND" else "PLAY_UNAVAILABLE")
            }
            String(response.body(), Charsets.UTF_8)
        } catch (e: CancellationException) { throw e }
        catch (e: PlayVerificationFailure) { throw e }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw PlayVerificationFailure("PLAY_UNAVAILABLE") }
        catch (_: Exception) { throw PlayVerificationFailure("PLAY_UNAVAILABLE") }
    }

    companion object {
        fun fromPrivateFile(path: Path): GooglePlayPublisher {
            try {
                require(Files.size(path) in 1..65536)
                val bytes = Files.readAllBytes(path)
                val config = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
                // Only a trusted Google service-account key; never follow arbitrary token endpoints.
                require(config.text("type") == "service_account")
                require(config.text("token_uri") == "https://oauth2.googleapis.com/token")
                val credentials = bytes.inputStream().use { ServiceAccountCredentials.fromStream(it) }
                    .createScoped(listOf("https://www.googleapis.com/auth/androidpublisher"))
                return GooglePlayPublisher(credentials)
            } catch (_: Exception) { throw PlayVerificationFailure("PLAY_CREDENTIALS_INVALID") }
        }
    }
}

internal class LimitedPlayBody(private val limit: Int = 262144) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    override fun getBody() = result
    override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
    override fun onNext(items: List<ByteBuffer>) {
        if (result.isDone) return
        for (item in items) {
            if (item.remaining() > limit - bytes.size()) {
                subscription?.cancel(); result.completeExceptionally(PlayVerificationFailure("PLAY_RESPONSE_INVALID")); return
            }
            val chunk = ByteArray(item.remaining()); item.get(chunk); bytes.write(chunk)
        }
        subscription?.request(1)
    }
    override fun onError(error: Throwable) { result.completeExceptionally(PlayVerificationFailure("PLAY_UNAVAILABLE")) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}
