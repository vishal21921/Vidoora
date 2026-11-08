package com.vidoora.backend.modules.auth

import com.vidoora.backend.common.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(authService: AuthService) {
    route("/api/v1/auth") {
        intercept(ApplicationCallPipeline.Call) { call.response.header(HttpHeaders.CacheControl,"no-store") }
        get("/password-reset/config") {call.respond(ApiResponse.success(mapOf("available" to authService.passwordResetEnabled)))}
        post("/password-reset/request") {
            if(!ResetRequestLimiter.allow(call.request.local.remoteHost)) {
                call.respond(HttpStatusCode.TooManyRequests,ApiResponse.error<Unit>("RATE_LIMIT","Wait before trying again"));return@post
            }
            authService.requestPasswordReset(call.receive<PasswordResetRequest>().email)
            call.respond(ApiResponse.success(mapOf("message" to "If this email is registered, we will send a reset code. Otherwise, create an account. Check spam and wait five minutes before retrying.")))
        }
        post("/password-reset/confirm") {
            val request=call.receive<PasswordResetConfirm>()
            authService.confirmPasswordReset(request.code,request.password)
            call.respond(ApiResponse.success(mapOf("message" to "Password updated. Log in with your new password.")))
        }
        post("/logout") {
            val request=call.receive<RefreshTokenRequest>()
            authService.logout(request.refreshToken)
            call.respond(HttpStatusCode.OK,ApiResponse.success(mapOf("accepted" to true)))
        }
        post("/register") {
            val request = call.receive<RegisterRequest>()
            val response = authService.register(request)
            call.respond(HttpStatusCode.Created, ApiResponse.success(response))
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            val response = authService.login(request)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        post("/refresh") {
            val request = call.receive<RefreshTokenRequest>()
            val response = authService.refreshToken(request.refreshToken)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }
    }

    authenticate("auth-jwt") {
        post("/api/v1/users/me/creator-invite") {
            val owner=call.principal<JWTPrincipal>()!!.subject!!
            call.respond(ApiResponse.success(authService.redeemCreatorInvite(owner,call.receive<CreatorInviteRequest>().code)))
        }
        get("/api/v1/users/me") {
            val principal = call.principal<JWTPrincipal>()
            val userId = principal?.subject ?: return@get call.respond(
                HttpStatusCode.Unauthorized,
                ApiResponse.error<Unit>("UNAUTHORIZED", "Invalid authentication token principal")
            )
            val profile = authService.getUserProfile(userId)
            call.respond(HttpStatusCode.OK, ApiResponse.success(profile))
        }
    }
}


@kotlinx.serialization.Serializable data class CreatorInviteRequest(val code: String) { override fun toString()="CreatorInviteRequest(<redacted>)" }

private object ResetRequestLimiter {
    private val requests=object: LinkedHashMap<String,Pair<Long,Int>>(256,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Pair<Long,Int>>?)=size>4096
    }
    @Synchronized fun allow(ip: String): Boolean {
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(ip.toByteArray()).joinToString(""){"%02x".format(it)}
        val now=System.nanoTime();val entry=requests[key]
        val current=if(entry==null || now-entry.first>java.util.concurrent.TimeUnit.MINUTES.toNanos(5)) now to 0 else entry
        if(current.second>=10)return false
        requests[key]=current.first to current.second+1;return true
    }
}
