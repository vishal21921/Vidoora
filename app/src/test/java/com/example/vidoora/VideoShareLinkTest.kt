package com.example.vidoora

import com.example.vidoora.core.network.SharedVideoLink
import com.example.vidoora.core.network.VideoShareAccess
import org.junit.Assert.*
import org.junit.Test

class VideoShareLinkTest {
    private val id="11111111-1111-1111-1111-111111111111"
    @Test fun publicAndUnlistedLinksParseWithoutQueryTokens() {
        assertEquals(id,SharedVideoLink.parse("vidoora://watch/$id")?.videoId)
        val shared=SharedVideoLink.parse("vidoora://watch/$id#aaa.bbb.ccc")
        assertNotNull(shared); assertTrue(shared!!.token=="aaa.bbb.ccc")
        assertFalse(shared.toString().contains("aaa.bbb.ccc"))
    }
    @Test fun foreignMalformedAndOversizedLinksAreRejected() {
        for(value in listOf("https://example.test/$id","vidoora://attacker/$id","vidoora://watch/1-1-1-1-1",
            "vidoora://watch/$id?token=aaa.bbb.ccc","vidoora://watch/$id/extra","vidoora://watch/$id#bad\nheader",
            "vidoora://user@watch/$id","vidoora://watch:443/$id","vidoora://watch/$id#"+"a".repeat(5000))) assertNull(SharedVideoLink.parse(value))
    }
    @Test fun capabilitiesAreBoundToVideoAndClearedOnSessionChange() {
        val store=VideoShareAccess()
        store.remember(SharedVideoLink(id,"aaa.bbb.ccc"))
        assertNotNull(store.token(id)); assertNull(store.token("22222222-2222-2222-2222-222222222222"))
        store.clear(); assertNull(store.token(id))
        store.remember(SharedVideoLink(id,"aaa.bbb.ccc")); store.remember(SharedVideoLink(id)); assertNull(store.token(id))
    }
    @Test fun newUploadsDefaultToPrivate() {
        val request=com.example.vidoora.core.network.dto.InitiateUploadRequestDto(title="Fixture",fileSizeBytes=10,mimeType="video/mp4")
        assertEquals("PRIVATE",request.visibility)
    }
}

