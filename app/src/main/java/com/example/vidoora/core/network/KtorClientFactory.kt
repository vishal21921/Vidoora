package com.example.vidoora.core.network

import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.core.network.dto.RefreshTokenRequestDto
import com.example.vidoora.core.network.dto.TokenResponseDto
import com.example.vidoora.core.security.SecureStorageManager
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import com.example.vidoora.core.security.SessionRequestGuard
import com.example.vidoora.core.security.RefreshOutcome
import com.example.vidoora.core.security.SessionRefreshCoordinator
import com.example.vidoora.core.security.SessionRevocationWorker
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KtorClientFactory @Inject constructor(
    private val secureStorageManager: SecureStorageManager,
    @ApplicationContext private val context: Context
) {
    val json: Json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun createClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }

            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
                sanitizeHeader { it.equals(HttpHeaders.Authorization,true) || it.equals("X-Vidoora-Share",true) }
                filter { !it.url.encodedPath.startsWith("/api/v1/downloads") && !it.url.encodedPath.startsWith("/api/v1/payments") && !it.url.encodedPath.endsWith("/share-link") && it.url.encodedPath!="/api/v1/videos/mine/page" }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30000L
                connectTimeoutMillis = 15000L
                socketTimeoutMillis = 30000L
            }

            defaultRequest {
                url(ApiConfig.BASE_URL)
                contentType(ContentType.Application.Json)
            }

            install(Auth) {
                bearer {
                    sendWithoutRequest { request ->
                        MediaEdgePolicy.apiOrEdge(request.url.build()) &&
                            !request.url.encodedPath.startsWith("/api/v1/auth/")
                    }
                    loadTokens {
                        secureStorageManager.tokenPair()?.let { BearerTokens(it.first,it.second) }
                    }
                    refreshTokens {
                        val request=response.call.request.url
                        if(!MediaEdgePolicy.apiOrEdge(request) ||
                            request.encodedPath.startsWith("/api/v1/auth/")) return@refreshTokens null
                        SessionRefreshCoordinator(secureStorageManager) { SessionRevocationWorker.enqueue(context) }
                            .refresh(SessionRequestGuard.failedAccess(response)) { currentRefreshToken ->
                                val refreshClient=HttpClient(OkHttp) {
                                    install(ContentNegotiation) { json(json) }
                                    install(HttpTimeout) { requestTimeoutMillis=30_000; connectTimeoutMillis=15_000 }
                                    defaultRequest { url(ApiConfig.BASE_URL); contentType(ContentType.Application.Json) }
                                }
                                try {
                                    val response=refreshClient.post("api/v1/auth/refresh") { setBody(RefreshTokenRequestDto(currentRefreshToken)) }
                                    if(response.status==HttpStatusCode.OK) {
                                        val data=response.body<ApiEnvelope<TokenResponseDto>>().takeIf { it.success }?.data
                                        if(data!=null) RefreshOutcome.Success(data.accessToken,data.refreshToken) else RefreshOutcome.Unavailable
                                    } else if(response.status.value in setOf(400,401,403)) RefreshOutcome.Rejected else RefreshOutcome.Unavailable
                                } finally { refreshClient.close() }
                            }
                    }
                }
            }
        }.also { SessionRequestGuard.install(it,secureStorageManager) }
    }
}





