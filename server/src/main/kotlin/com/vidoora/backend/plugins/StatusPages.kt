package com.vidoora.backend.plugins

import com.vidoora.backend.common.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
    if (pluginOrNull(StatusPages) == null) {
        install(StatusPages) {
            exception<com.vidoora.backend.modules.auth.InvalidRefreshToken> { call, _ ->
                call.response.header(HttpHeaders.CacheControl,"no-store")
                call.respond(HttpStatusCode.Unauthorized,ApiResponse.error<Unit>("SESSION_INVALID","Session expired or no longer valid. Please sign in again."))
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<Unit>("BAD_REQUEST", cause.message ?: "Invalid request parameters")
                )
            }
            exception<IllegalStateException> { call, cause ->
                call.respond(
                    HttpStatusCode.Forbidden,
                    ApiResponse.error<Unit>("FORBIDDEN", cause.message ?: "Action forbidden or unauthorized")
                )
            }
            exception<kotlinx.coroutines.CancellationException> { _, cause -> throw cause }
            exception<Throwable> { call, cause ->
                call.application.environment.log.error("Unhandled Exception", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse.error<Unit>("INTERNAL_ERROR", "An unexpected internal server error occurred")
                )
            }
        }
    }
}

