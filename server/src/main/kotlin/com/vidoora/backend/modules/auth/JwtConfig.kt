package com.vidoora.backend.modules.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.*
import java.util.Date
import java.util.UUID
import java.time.Instant

class JwtConfig(config: ApplicationConfig) {
    val secret: String = config.property("jwt.secret").getString().also {
        require(it.length >= 32) { "JWT secret must contain at least 32 characters" }
    }
    val issuer: String = config.propertyOrNull("jwt.issuer")?.getString() ?: "https://vidoora.app"
    val audience: String = config.propertyOrNull("jwt.audience")?.getString() ?: "https://vidoora.app/api"
    val realm: String = config.propertyOrNull("jwt.realm")?.getString() ?: "Vidoora API Access"
    val accessLifetimeMs: Long = config.propertyOrNull("jwt.accessLifetimeMs")?.getString()?.toLong() ?: 900000L // 15 mins
    val refreshLifetimeMs: Long = config.propertyOrNull("jwt.refreshLifetimeMs")?.getString()?.toLong() ?: 2592000000L // 30 days

    private val algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun generateAccessToken(userId: String, email: String, role: String): String {
        return JWT.create()
            .withSubject(userId)
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("email", email)
            .withClaim("role", role)
            .withClaim("type", "access")
            .withJWTId(UUID.randomUUID().toString())
            .withExpiresAt(Date(System.currentTimeMillis() + accessLifetimeMs))
            .sign(algorithm)
    }

    fun generateRefreshToken(userId: String): String {
        return JWT.create()
            .withSubject(userId)
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("type", "refresh")
            .withJWTId(UUID.randomUUID().toString())
            .withExpiresAt(Date(System.currentTimeMillis() + refreshLifetimeMs))
            .sign(algorithm)
    }
    fun generateSessionRefreshToken(userId: String,sessionId: UUID,expiresAt: Instant): String = JWT.create()
        .withSubject(userId).withIssuer(issuer).withAudience(audience)
        .withClaim("type","refresh").withClaim("sid",sessionId.toString())
        .withJWTId(UUID.randomUUID().toString()).withExpiresAt(Date.from(expiresAt)).sign(algorithm)
}

