package com.vidoora.backend

import com.vidoora.backend.modules.membership.*
import com.vidoora.backend.modules.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.UUID

class ViewerEntitlementTest {
    private val account=UUID.randomUUID()
    private class MutableClock(var time: Instant): Clock() {
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone: ZoneId)=this
        override fun instant()=time
    }
    private val now=Instant.parse("2026-10-30T00:00:00Z")
    private fun grant(owner: UUID=account,end: Instant=now.plusSeconds(3600),ack: Boolean=true)=
        VerifiedViewerGrant(owner.toString(),"MONTHLY","fixture-period",now.minusSeconds(60),end,VerifiedPaymentState.PURCHASED,ack)
    @Test fun freeGuestsNeverCallStoreAndCacheCannotCrossAccounts()=runBlocking {
        var calls=0
        val service=VerifiedViewerEntitlements(VerifiedGrantSource { calls++;listOf(grant()) },MutableClock(now))
        assertFalse(service.resolve(null).adFree);assertEquals(0,calls)
        assertTrue(service.resolve(account.toString()).adFree)
        assertFalse(service.resolve(UUID.randomUUID().toString()).adFree);assertEquals(2,calls)
    }
    @Test fun cacheExpiresAtStoreBoundaryAndDoesNotSurviveClockRollback()=runBlocking {
        val clock=MutableClock(now);var calls=0
        val service=VerifiedViewerEntitlements(VerifiedGrantSource { calls++;listOf(grant(end=now.plusSeconds(20))) },clock)
        assertEquals(20L,service.resolve(account.toString()).validForSeconds)
        clock.time=now.plusSeconds(19);assertTrue(service.resolve(account.toString()).adFree);assertEquals(1,calls)
        clock.time=now.plusSeconds(20);assertFalse(service.resolve(account.toString()).adFree);assertEquals(2,calls)
        clock.time=now.minusSeconds(1);service.resolve(account.toString());assertEquals(3,calls)
    }
    @Test fun refundsOrRevocationAreObservedWithinSixtySeconds()=runBlocking {
        val clock=MutableClock(now);var active=true;var calls=0
        val service=VerifiedViewerEntitlements(VerifiedGrantSource { calls++;if(active) listOf(grant()) else emptyList() },clock)
        assertTrue(service.resolve(account.toString()).adFree);active=false
        clock.time=now.plusSeconds(59);assertTrue(service.resolve(account.toString()).adFree)
        clock.time=now.plusSeconds(60);assertFalse(service.resolve(account.toString()).adFree);assertEquals(2,calls)
    }
    @Test fun unverifiedAndFailedSourcesNeverBecomePaidAccess()=runBlocking {
        val pending=VerifiedViewerEntitlements(VerifiedGrantSource { listOf(grant(ack=false)) },MutableClock(now))
        assertFalse(pending.resolve(account.toString()).adFree)
        val failed=VerifiedViewerEntitlements(VerifiedGrantSource { throw IllegalStateException("fixture") },MutableClock(now))
        assertEquals(480,failed.resolve(account.toString()).maxHeight)
    }
    @Test fun simultaneousRequestsForSameAccountShareOneVerification()=runBlocking {
        var calls=0
        val service=VerifiedViewerEntitlements(VerifiedGrantSource { calls++;delay(10);listOf(grant()) },MutableClock(now))
        (1..12).map { async { assertTrue(service.resolve(account.toString()).adFree) } }.awaitAll()
        assertEquals(1,calls)
    }
    @Test fun freeMasterDropsWholeHighVariantsAndDirectHighPathsAreBlocked() {
        val text="#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360\n360p/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480\n480p/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\n720p/index.m3u8\n"
        val filtered=PlaybackQuality.master(text,480)
        assertTrue(filtered.contains("360p/"));assertTrue(filtered.contains("480p/"));assertFalse(filtered.contains("720"));assertFalse(filtered.contains("900000"))
        assertEquals(text,PlaybackQuality.master(text,null))
        assertFalse(PlaybackQuality.permits("720p/index.m3u8",480));assertFalse(PlaybackQuality.permits("720p/segment_000001.ts",480))
        assertTrue(PlaybackQuality.permits("480p/segment_000001.ts",480));assertTrue(PlaybackQuality.permits("720p/segment_000001.ts",null))
    }
    @Test(expected=PlaybackQualityRestricted::class) fun highOnlySourceNeverFallsBackAboveFreeLimit() {
        PlaybackQuality.master("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\n720p/index.m3u8\n",480)
    }
}
