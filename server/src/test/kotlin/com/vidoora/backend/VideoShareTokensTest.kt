package com.vidoora.backend

import com.vidoora.backend.modules.auth.JwtConfig
import com.vidoora.backend.modules.playback.VideoShareTokens
import io.ktor.server.config.MapApplicationConfig
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class VideoShareTokensTest {
    @Test fun compactLinksPreserveExpiryAndBindingWhileLegacyLinksRemainValid() {
        val tokens = VideoShareTokens(JwtConfig(MapApplicationConfig("jwt.secret" to "share-test-secret-at-least-32-characters")))
        val video = UUID.randomUUID()
        val version = UUID.randomUUID()
        val expiry = Instant.ofEpochSecond(Instant.now().epochSecond + 60)
        val compact = tokens.issueCompact(video, version, expiry)
        val legacy = tokens.issue(video, version, expiry)
        assertTrue(compact.length < legacy.length / 4)
        assertEquals(expiry, tokens.expiry(compact, video, version))
        assertEquals(expiry, tokens.expiry(legacy, video, version))
        assertFalse(tokens.valid(compact, UUID.randomUUID(), version))
        assertFalse(tokens.valid(compact, video, UUID.randomUUID()))
        val altered = compact.substringBeforeLast('.') + "." +
            (if (compact.substringAfterLast('.').first() == 'A') 'B' else 'A') +
            compact.substringAfterLast('.').drop(1)
        assertFalse(tokens.valid(altered, video, version))
        assertFalse(tokens.valid(tokens.issueCompact(video, version, Instant.now().minusSeconds(5)), video, version))
    }
}
