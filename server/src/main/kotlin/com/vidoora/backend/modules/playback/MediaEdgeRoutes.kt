package com.vidoora.backend.modules.playback

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

data class MediaEdgeConfig(val enabled: Boolean=false,val secret: String="") {
    init { require(!enabled || secret.length in 32..256) { "Enabled media edge requires a separate 32–256 character secret" } }
    fun accepts(value: String?): Boolean = enabled && value!=null && value.length==secret.length &&
        MessageDigest.isEqual(secret.toByteArray(Charsets.UTF_8),value.toByteArray(Charsets.UTF_8))
    companion object {
        fun environment()=MediaEdgeConfig(System.getenv("MEDIA_EDGE_ENABLED")=="true",System.getenv("MEDIA_EDGE_SECRET") ?: "")
    }
}
@Serializable private data class EdgeAssetRequest(val videoId: String,val asset: String)
@Serializable private data class EdgeAssetGrant(val bucket: String,val key: String)

fun Route.mediaEdgeRoutes(service: PlaybackService,config: MediaEdgeConfig) {
    authenticate("auth-jwt",strategy=AuthenticationStrategy.Optional) {
        post("/api/v1/internal/media/authorize") {
            call.response.header(HttpHeaders.CacheControl,"no-store")
            if(!config.accepts(call.request.headers["X-Vidoora-Edge"])) {
                call.respond(HttpStatusCode.NotFound);return@post
            }
            if((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L)>1024L) {
                call.respond(HttpStatusCode.BadRequest);return@post
            }
            val input=call.receiveChannel();val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
            try {
                while(true) {
                    val n=input.readAvailable(buffer,0,buffer.size);if(n<0) break
                    require(bytes.size()+n<=1024) { "Invalid edge request" };bytes.write(buffer,0,n)
                }
            } finally { input.cancel(null) }
            val request=try { Json.decodeFromString<EdgeAssetRequest>(bytes.toString(Charsets.UTF_8)) }
                catch(_: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest);return@post }
            if(!PlaybackPaths.validAsset(request.asset) || !request.asset.endsWith(".ts")) {
                call.respond(HttpStatusCode.NotFound);return@post
            }
            try {
                val grant=service.authorizeAsset(call.principal<JWTPrincipal>()?.subject,request.videoId,request.asset,call.request.headers["X-Vidoora-Share"])
                call.respond(EdgeAssetGrant(grant.bucket,grant.key))
            } catch(_: PlaybackDenied) { call.respond(HttpStatusCode.NotFound) }
              catch(_: PlaybackUnavailable) { call.respond(HttpStatusCode.NotFound) }
              catch(_: PlaybackQualityRestricted) { call.respond(HttpStatusCode.Forbidden) }
        }
    }
}
