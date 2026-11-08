package com.vidoora.backend.plugins

import com.vidoora.backend.common.ApiResponse
import com.vidoora.backend.modules.auth.JwtConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity(jwtConfig: JwtConfig) {
    if (pluginOrNull(Authentication) == null) {
        authentication {
            jwt("auth-jwt") {
                realm = jwtConfig.realm
                verifier(jwtConfig.verifier)
                validate { credential ->
                    if (credential.payload.subject != null && credential.payload.getClaim("type").asString() == "access") {
                        JWTPrincipal(credential.payload)
                    } else {
                        null
                    }
                }
                challenge { _, _ ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.error<Unit>("UNAUTHORIZED", "Token is invalid, expired, or missing")
                    )
                }
            }
        }
    }
}
