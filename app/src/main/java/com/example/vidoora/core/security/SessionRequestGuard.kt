package com.example.vidoora.core.security

import com.example.vidoora.core.network.ApiConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.*

/** Ktor may finish caching a refresh after clearToken(); check durable identity at each actual send. */
object SessionRequestGuard {
    fun failedAccess(response: io.ktor.client.statement.HttpResponse): String? =
        response.call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    fun install(client: HttpClient,storage: SecureStorageManager) {
        client.sendPipeline.intercept(HttpSendPipeline.State) {
            val request=context
            val own=com.example.vidoora.core.network.MediaEdgePolicy.apiOrEdge(request.url.build())
            val current=storage.getAccessToken()
            request.headers.remove(HttpHeaders.Authorization)
            if(own && !request.url.encodedPath.startsWith("/api/v1/auth/") && current!=null) {
                // Use the current identity; do not accidentally downgrade a signed-in blocked viewer to guest.
                request.headers.append(HttpHeaders.Authorization,"Bearer $current")
            }
            if(!own) request.headers.remove("X-Vidoora-Share")
            proceed()
        }
    }
}


