package com.vidoora.backend.modules.membership

import com.vidoora.backend.common.ApiResponse
import com.vidoora.backend.modules.auth.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.membershipRoutes(authService: AuthService, entitlements: ViewerEntitlements) {
    authenticate("auth-jwt", optional = true) {
        get("/api/v1/membership") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val account = call.principal<JWTPrincipal>()?.subject
            if (account != null && authService.getUserProfile(account).isSuspended) {
                call.respond(HttpStatusCode.Forbidden,ApiResponse.error<Unit>("ACCOUNT_UNAVAILABLE","Account unavailable"))
                return@get
            }
            call.respond(ApiResponse.success(entitlements.resolve(account)))
        }
    }
}
