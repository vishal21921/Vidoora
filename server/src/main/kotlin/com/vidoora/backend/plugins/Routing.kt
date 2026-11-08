package com.vidoora.backend.plugins

import com.vidoora.backend.modules.downloads.downloadRoutes
import com.vidoora.backend.modules.membership.paymentRoutes
import com.vidoora.backend.modules.membership.membershipRoutes
import com.vidoora.backend.common.ApiResponse
import com.vidoora.backend.modules.auth.AuthService
import com.vidoora.backend.modules.auth.authRoutes
import com.vidoora.backend.modules.upload.UploadService
import com.vidoora.backend.modules.upload.uploadRoutes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.path
import io.ktor.server.routing.*
import org.slf4j.event.Level
import com.vidoora.backend.modules.playback.PlaybackService
import com.vidoora.backend.modules.playback.playbackRoutes
import com.vidoora.backend.modules.playback.mediaEdgeRoutes
import com.vidoora.backend.modules.analytics.analyticsRoutes

fun Application.configureRouting(
    authService: AuthService,
    uploadService: UploadService,
    playbackService: PlaybackService? = null,
    entitlements: com.vidoora.backend.modules.membership.ViewerEntitlements = com.vidoora.backend.modules.membership.FreeViewerEntitlements,
    recovery: com.vidoora.backend.modules.membership.PurchaseRecovery? = null,
    downloads: com.vidoora.backend.modules.downloads.PrivateDownloads? = null,
    analytics: com.vidoora.backend.modules.analytics.VideoAnalytics? = null
) {
    if (pluginOrNull(CallLogging) == null) {
        install(CallLogging) {
            level = Level.INFO
            filter { it.request.path()!="/api/v1/videos/mine/page" && !it.request.path().startsWith("/api/v1/payments") && !it.request.path().startsWith("/api/v1/downloads") && !it.request.path().startsWith("/api/v1/analytics") }
        }
    }

    if (pluginOrNull(CORS) == null) {
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            anyHost()
        }
    }

    routing {
        analytics?.let { analyticsRoutes(it) }
        get("/health") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("status" to "UP", "version" to "1.0.0")))
        }

        downloads?.let { downloadRoutes(it) }
        paymentRoutes(authService,recovery)
        membershipRoutes(authService,entitlements)
        authRoutes(authService)
        uploadRoutes(uploadService)
        if (playbackService != null) {
            playbackRoutes(playbackService)
            mediaEdgeRoutes(playbackService,com.vidoora.backend.modules.playback.MediaEdgeConfig.environment())
        }
    }
}

