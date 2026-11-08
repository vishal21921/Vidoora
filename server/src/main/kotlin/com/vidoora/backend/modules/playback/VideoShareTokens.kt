package com.vidoora.backend.modules.playback

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vidoora.backend.modules.auth.JwtConfig
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A video-specific capability, never an API access token or an R2 credential. */
class VideoShareTokens(config: JwtConfig) {
    private val algorithm=Algorithm.HMAC256(config.secret)
    private val compactKey=SecretKeySpec(config.secret.toByteArray(Charsets.UTF_8),"HmacSHA256")
    private val compactFormat=Regex("s1\\.([0-9a-z]{1,12})\\.([A-Za-z0-9_-]{22})")
    private val issuer=config.issuer
    private val verifier=JWT.require(algorithm).withIssuer(issuer).withAudience("vidoora-video-share")
        .withClaim("type","video_share").build()
    fun issue(videoId: UUID,version: UUID,expiresAt: Instant=Instant.now().plusSeconds(30L*86400)): String = JWT.create()
        .withIssuer(issuer).withAudience("vidoora-video-share").withSubject(videoId.toString())
        .withClaim("type","video_share").withClaim("version",version.toString())
        .withExpiresAt(Date.from(expiresAt)).sign(algorithm)
    fun issueCompact(videoId: UUID, version: UUID, expiresAt: Instant): String {
        val expiry = expiresAt.epochSecond.toString(36)
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(videoId, version, expiry))
        return "s1.$expiry.$signature"
    }

    private fun sign(videoId: UUID, version: UUID, expiry: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(compactKey)
        return mac.doFinal("vidoora-video-share:s1:$issuer:$videoId:$version:$expiry".toByteArray(Charsets.UTF_8))
            .copyOf(16)
    }

    fun expiry(token: String?, videoId: UUID, version: UUID): Instant? {
        if (token.isNullOrBlank() || token.length > 4096) return null
        return try {
            if (token.startsWith("s1.")) {
                val parts = compactFormat.matchEntire(token) ?: return null
                val (encodedExpiry, encodedSignature) = parts.destructured
                val expiresAt = Instant.ofEpochSecond(encodedExpiry.toLong(36))
                val supplied = Base64.getUrlDecoder().decode(encodedSignature)
                val expected = sign(videoId, version, encodedExpiry)
                expiresAt.takeIf {
                    it.isAfter(Instant.now()) && MessageDigest.isEqual(supplied, expected) &&
                        Base64.getUrlEncoder().withoutPadding().encodeToString(supplied) == encodedSignature
                }
            } else {
                val jwt = verifier.verify(token)
                jwt.expiresAt?.toInstant()?.takeIf {
                    jwt.subject == videoId.toString() && jwt.getClaim("version").asString() == version.toString()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun valid(token: String?, videoId: UUID, version: UUID): Boolean = expiry(token, videoId, version) != null
}
