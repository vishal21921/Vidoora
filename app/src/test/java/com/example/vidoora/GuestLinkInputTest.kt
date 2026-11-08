package com.example.vidoora

import com.example.vidoora.core.network.SharedVideoLink
import org.junit.Assert.*
import org.junit.Test
import com.example.vidoora.feature.home.VideoLinkEntry
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class GuestLinkInputTest {
    private val base="vidoora://watch/11111111-1111-1111-1111-111111111111"
    @Test fun browserIntentCapabilityIsValidatedAndExistingFragmentWins() {
        val link="vidoora://watch/388f89c6-5e82-40d3-9dbe-24611d3bb67e"
        assertEquals("aaa.bbb.ccc",SharedVideoLink.parseIncoming(link,"aaa.bbb.ccc")?.token)
        assertNull(SharedVideoLink.parseIncoming(link,"invalid;intent"))
        assertEquals("aaa.bbb.ccc",SharedVideoLink.parseIncoming("$link#aaa.bbb.ccc","other.value.token")?.token)
    }
    @Test fun guestPasteRequiresCompleteCapabilityInsteadOfVideoId() {
        assertNull(SharedVideoLink.parseShared(base))
        assertNull(SharedVideoLink.parseShared("$base#"))
        assertNull(SharedVideoLink.parseShared("$base#aaa.bbb"))
        val link=SharedVideoLink.parseShared("  $base#aaa.bbb.ccc  ")!!
        assertEquals("11111111-1111-1111-1111-111111111111",link.videoId)
        assertEquals("aaa.bbb.ccc",link.token)
        assertFalse(link.toString().contains("aaa.bbb.ccc"))
    }
    @Test fun guestPasteRejectsForeignHostsQuerySecretsAndOversizedInput() {
        for(value in listOf("https://example.test/$base#aaa.bbb.ccc","$base?token=aaa.bbb.ccc", "$base#"+"x".repeat(4600),"$base/extra#aaa.bbb.ccc")) {
            assertNull(SharedVideoLink.parseShared(value))
        }
    }
    @Test fun pasteAutomaticallyOpensOnceWithoutSubmitAndClearsSecretInput() = runTest {
        val opened=mutableListOf<SharedVideoLink>()
        val input=VideoLinkEntry(this) { opened+=it }
        input.change("$base#aaa.bbb.ccc")
        advanceTimeBy(399); runCurrent(); assertTrue(opened.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(1,opened.size)
        assertEquals("",input.state.value.text)
        assertFalse(input.state.value.invalid)
        advanceTimeBy(1000); runCurrent(); assertEquals(1,opened.size)
    }
    @Test fun replacementEditCancelsPendingOpenAndInvalidInputDoesNotNavigate() = runTest {
        var opened=0
        val input=VideoLinkEntry(this) { opened++ }
        input.change("$base#aaa.bbb.ccc")
        advanceTimeBy(200); input.change(base)
        advanceTimeBy(500); runCurrent()
        assertEquals(0,opened); assertTrue(input.state.value.invalid)
        assertFalse(input.state.value.toString().contains(base))
        input.change("$base#aaa.bbb.ccc"); input.change("")
        advanceTimeBy(500); runCurrent(); assertEquals(0,opened)
    }}

