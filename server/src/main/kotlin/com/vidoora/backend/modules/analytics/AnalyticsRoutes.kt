package com.vidoora.backend.modules.analytics

import com.vidoora.backend.common.ApiResponse
import com.vidoora.backend.modules.playback.PlaybackDenied
import com.vidoora.backend.modules.playback.PlaybackUnavailable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

fun Route.analyticsRoutes(service: VideoAnalytics) {
    get("/analytics") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondText(checkNotNull(object {}.javaClass.getResource("/analytics/index.html")).readText(), ContentType.Text.Html)
    }
    for ((file, type) in listOf("dashboard.js" to ContentType.Application.JavaScript, "style.css" to ContentType.Text.CSS)) {
        get("/analytics/$file") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondText(checkNotNull(object {}.javaClass.getResource("/analytics/$file")).readText(), type)
        }
    }
    authenticate("auth-jwt") {
        route("/api/v1/analytics") {
            get("/service-health") { call.analyticsAccess {
                respond(ApiResponse.success(service.operations.read(analyticsUser())))
            } }
            get("/ads") { call.analyticsAccess {
                respond(ApiResponse.success(service.ads.report(analyticsUser(), request.queryParameters["from"], request.queryParameters["to"])))
            } }
            post("/creator-invites") { call.analyticsAccess {
                respond(HttpStatusCode.Created, ApiResponse.success(service.ads.invite(analyticsUser())))
            } }
            get("/payments") { call.analyticsAccess {
                val query = request.queryParameters
                respond(ApiResponse.success(service.payments(analyticsUser(), query["days"]?.toInt() ?: 30,
                    query["status"] ?: "ALL", query["search"], query.page())))
            } }
            get("/upload-health") { call.analyticsAccess {
                val query = request.queryParameters
                respond(ApiResponse.success(service.uploadHealth(analyticsUser(), query["status"] ?: "ALL", query.page())))
            } }
            get("/uploaders") { call.analyticsAccess {
                val query = request.queryParameters
                respond(ApiResponse.success(service.uploaders(analyticsUser(), query["from"], query["to"],
                    query["search"], query["sort"], query.page(), false)))
            } }
            get("/uploaders/manage") { call.analyticsAccess {
                val query = request.queryParameters
                respond(ApiResponse.success(service.uploaders(analyticsUser(), null, null,
                    query["search"], "name", query.page(), true)))
            } }
            delete("/uploaders/{id}") { call.analyticsAccess {
                service.removeUploader(analyticsUser(), parameters["id"].orEmpty())
                respond(HttpStatusCode.NoContent)
            } }
            get("/videos") { call.analyticsAccess {
                val query = request.queryParameters
                respond(ApiResponse.success(service.catalogVideos(analyticsUser(), query["creator"], query["from"],
                    query["to"], query["search"], query["sort"], query.page())))
            } }
            get("/videos/{video}/thumbnail") { call.analyticsAccess {
                respondBytes(service.adminThumbnail(analyticsUser(), parameters["video"].orEmpty()), ContentType.Image.JPEG)
            } }
            get("/videos/{video}/link") { call.analyticsAccess {
                respond(ApiResponse.success(service.adminShareLink(analyticsUser(), parameters["video"].orEmpty())))
            } }
        }
        get("/api/v1/analytics/report") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            try {
                val query = call.request.queryParameters
                call.respond(ApiResponse.success(service.report(call.principal<JWTPrincipal>()!!.subject!!,
                    query["from"], query["to"], query["creator"], query["video"])))
            } catch (_: PlaybackDenied) {
                call.respond(HttpStatusCode.Forbidden, ApiResponse.error<Unit>("FORBIDDEN", "Creator or admin access required"))
            }
        }
    }
    authenticate("auth-jwt", strategy = AuthenticationStrategy.Optional) {
        post("/api/v1/analytics/ads/event") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (!AdEventThrottle.allow(call.request.local.remoteHost)) {
                call.respond(HttpStatusCode.TooManyRequests); return@post
            }
            val input = call.receiveChannel()
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            try {
                while(true) {
                    val size = input.readAvailable(buffer,0,buffer.size)
                    if(size<0) break
                    require(bytes.size()+size<=1024) { "Event exceeds size limit" }
                    bytes.write(buffer,0,size)
                }
            } finally { input.cancel(null) }
            service.ads.record(Json.decodeFromString<AdSdkEvent>(bytes.toString(Charsets.UTF_8)))
            call.respond(HttpStatusCode.Accepted, ApiResponse.success(AnalyticsAccepted()))
        }
        post("/api/v1/analytics/videos/{video}/event") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            require(length == null || length <= 1024) { "Event exceeds size limit" }
            try {
                val input = call.receiveChannel()
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                try {
                    while (true) {
                        val size = input.readAvailable(buffer, 0, buffer.size)
                        if (size < 0) break
                        require(bytes.size() + size <= 1024) { "Event exceeds size limit" }
                        bytes.write(buffer, 0, size)
                    }
                } finally { input.cancel(null) }
                val request = Json.decodeFromString<AnalyticsEvent>(bytes.toString(Charsets.UTF_8))
                val identity = call.principal<JWTPrincipal>()?.subject ?: request.viewerKey
                if (!AnalyticsThrottle.allow(identity)) {
                    call.respond(HttpStatusCode.TooManyRequests, ApiResponse.error<Unit>("RATE_LIMIT", "Retry later"))
                    return@post
                }
                service.event(call.principal<JWTPrincipal>()?.subject, call.parameters["video"].orEmpty(),
                    call.request.headers["X-Vidoora-Share"], request)
                call.respond(HttpStatusCode.Accepted, ApiResponse.success(AnalyticsAccepted()))
            } catch (_: PlaybackDenied) {
                call.respond(HttpStatusCode.NotFound, ApiResponse.error<Unit>("NOT_FOUND", "Video unavailable"))
            } catch (_: PlaybackUnavailable) {
                call.respond(HttpStatusCode.NotFound, ApiResponse.error<Unit>("NOT_FOUND", "Video unavailable"))
            }
        }
    }
}

private object AdEventThrottle {
    private val buckets = object : LinkedHashMap<String, Pair<Long,Int>>(256,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Pair<Long,Int>>?) = size>4096
    }
    private var globalStart = 0L
    private var globalCount = 0
    @Synchronized fun allow(ip: String): Boolean {
        val now=System.nanoTime()
        if(now-globalStart>60_000_000_000L) { globalStart=now; globalCount=0 }
        if(globalCount>=6000) return false
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(ip.toByteArray()).joinToString("") { "%02x".format(it) }
        val old=buckets[key]
        val entry=if(old==null || now-old.first>60_000_000_000L) now to 0 else old
        if(entry.second>=600) return false
        buckets[key]=entry.first to entry.second+1; globalCount++
        return true
    }
}

private fun Parameters.page() = this["page"]?.toInt() ?: 0
private fun ApplicationCall.analyticsUser() = principal<JWTPrincipal>()!!.subject!!

private suspend fun ApplicationCall.analyticsAccess(action: suspend ApplicationCall.() -> Unit) {
    response.header(HttpHeaders.CacheControl, "no-store")
    try {
        action()
    } catch (_: PlaybackDenied) {
        respond(HttpStatusCode.Forbidden, ApiResponse.error<Unit>("FORBIDDEN", "Access denied"))
    } catch (_: PlaybackUnavailable) {
        respond(HttpStatusCode.NotFound, ApiResponse.error<Unit>("NOT_FOUND", "Item unavailable"))
    }
}

private object AnalyticsThrottle {
    private val buckets = object : LinkedHashMap<String, Pair<Long, Int>>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Int>>?) = size > 4096
    }
    @Synchronized fun allow(identity: String): Boolean {
        val now = System.nanoTime()
        val previous = buckets[identity]
        val bucket = if (previous == null || now - previous.first >= 60_000_000_000L) now to 0 else previous
        if (bucket.second >= 60) return false
        buckets[identity] = bucket.first to bucket.second + 1
        return true
    }
}
