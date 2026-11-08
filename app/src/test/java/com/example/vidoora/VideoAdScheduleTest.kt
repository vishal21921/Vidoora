package com.example.vidoora

import com.example.vidoora.feature.ads.VideoAdSchedule
import com.example.vidoora.feature.ads.VideoWatchAdClock
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class VideoAdScheduleTest {
    @Test fun breakIsDueAfterTenMinutesActuallyWatchedAndStartsANewInterval() {
        val clock=VideoWatchAdClock(); clock.update(0,true); clock.update(599_999,true)
        assertFalse(clock.takeBreak(1_000_000)); clock.update(600_000,true)
        assertTrue(clock.takeBreak(1_000_000)); assertFalse(clock.takeBreak(1_000_000))
        clock.update(1_200_000,true); assertTrue(clock.takeBreak(1_000_000))
    }
    @Test fun pausesBufferingAndBackgroundGapsAddNoViewingTime() {
        val clock=VideoWatchAdClock(); clock.update(0,true); clock.update(100_000,false)
        clock.update(9_000_000,false); assertEquals(100_000L,clock.watchedMs)
        clock.update(10_000_000,true); clock.update(10_500_000,true)
        assertTrue(clock.takeBreak(100_000))
    }
    @Test fun adDurationIsExcludedAndNoBreakIsRequestedWhileNotWatching() {
        val clock=VideoWatchAdClock(); clock.update(0,true); clock.update(600_000,false)
        assertFalse(clock.takeBreak(100_000)); clock.update(1_000_000,true)
        assertTrue(clock.takeBreak(100_000)); clock.update(1_000_000,false)
        clock.update(8_000_000,false); assertEquals(0L,clock.watchedMs)
    }
    @Test fun seekingNearAnEndingDoesNotEarnTenMinutesOrCreateAnEndingAd() {
        val clock=VideoWatchAdClock(); clock.update(0,true); clock.update(1_000,true)
        assertFalse(clock.takeBreak(20_000)); clock.update(600_000,true)
        assertFalse(clock.takeBreak(10_000)); assertTrue(clock.takeBreak(10_001))
    }
    @Test fun newPlayerHasNoOtherVideoBudgetAndClockChangesCannotOverflow() {
        val first=VideoWatchAdClock(); first.update(100,true); first.update(600_100,true)
        assertTrue(first.takeBreak(100_000))
        val second=VideoWatchAdClock(); second.update(100,true); second.update(50,true)
        assertEquals(0L,second.watchedMs); assertFalse(second.takeBreak(100_000))
        second.update(Long.MAX_VALUE,true); assertEquals(600_000L,second.watchedMs)
    }
    @Test fun explicitlyArmedBreakXmlIsOneValidSdkBreakWithOnlyDemoInventory() {
        val xml=VideoAdSchedule.breakXml()
        val factory=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
        val doc=factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val breaks=doc.getElementsByTagNameNS("http://www.iab.net/videosuite/vmap","AdBreak")
        assertEquals(1,breaks.length); assertEquals("start",breaks.item(0).attributes.getNamedItem("timeOffset").nodeValue)
        assertTrue(doc.getElementsByTagNameNS("http://www.iab.net/videosuite/vmap","AdTagURI").item(0).textContent.startsWith(VideoAdSchedule.SAMPLE_TAG))
        assertFalse(xml.contains("api/v1")); assertFalse(xml.contains("X-Vidoora"))
    }
}

