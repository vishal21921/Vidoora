package com.example.vidoora

import com.example.vidoora.core.network.MediaEdgePolicy
import io.ktor.http.Url
import org.junit.Assert.*
import org.junit.Test

class MediaEdgePolicyTest {
    private val path="/api/v1/videos/11111111-1111-1111-1111-111111111111/playback/360p/segment_000000.ts"
    private val edge="https://media.example/"
    @Test fun segmentOnlyRoutingAndDefaultOff() {
        val original=Url("https://api.example$path")
        assertEquals(original,MediaEdgePolicy.target(original,""))
        assertEquals("media.example",MediaEdgePolicy.target(original,edge).host)
        val playlist=Url("https://api.example"+path.substringBefore("360p/")+"master.m3u8")
        assertEquals(playlist,MediaEdgePolicy.target(playlist,edge))
    }
    @Test fun foreignOriginsQueriesAndNonMediaNeverReceiveIdentity() {
        assertTrue(MediaEdgePolicy.trusted(Url("https://media.example$path"),edge))
        for(value in listOf("https://evil.example$path","http://media.example$path","https://media.example$path?token=x",
            "https://media.example/api/v1/auth/login","https://media.example/api/v1/downloads","https://media.example$path#secret",
            "https://user:password@media.example$path","https://media.example:444$path"))
            assertFalse(value,MediaEdgePolicy.trusted(Url(value),edge))
    }
}
