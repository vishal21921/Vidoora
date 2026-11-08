package com.vidoora.backend

import com.vidoora.backend.modules.membership.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ViewerPlanPolicyTest {
    private val now=Instant.parse("2026-10-30T12:00:00Z")
    private fun grant(plan: String="MONTHLY",end: Instant=now.plusSeconds(86400))=VerifiedViewerGrant("alice",plan,"verified-period",now.minusSeconds(60),end,VerifiedPaymentState.PURCHASED,true)
    private fun access(plan: String="MONTHLY")=ViewerAccessPolicy.resolve("alice",grant(plan),now)
    @Test fun approvedPricesAndBenefitsNeverChangeWithDurationMath() {
        assertEquals(listOf(0,1300,3900,9900),ViewerPlans.all.map { it.pricePaise })
        assertEquals(listOf(0,0,5,30),ViewerPlans.all.map { it.downloadsPerPeriod })
        assertEquals(listOf(false,true,true,true),ViewerPlans.all.map { it.adFree })
        assertEquals(listOf(false,false,false,true),ViewerPlans.all.map { it.autoRenew })
        assertEquals("P1M",ViewerPlans.get("MONTHLY")!!.validity)
    }
    @Test fun freeAndGuestsStopAt480AndNeverGetDownloadsOrAdFree() {
        for(account in listOf(null,"alice")) {
            val free=ViewerAccessPolicy.resolve(account,null,now)
            assertTrue(free.canStream(480,720));assertFalse(free.canStream(720,720));assertFalse(free.adFree)
            assertEquals(DownloadDecision.PLAN_REQUIRED,ViewerDownloadPolicy.decide(free,"video",emptySet(),emptySet()))
        }
    }
    @Test fun verifiedPaidPlansRemoveAdsAndUnlockOnlyAvailableResolution() {
        for(plan in listOf("DAY","WEEK","MONTHLY")) {
            val paid=access(plan)
            assertTrue(paid.adFree);assertTrue(paid.canStream(720,720));assertFalse(paid.canStream(1080,720))
        }
        assertEquals(DownloadDecision.PLAN_REQUIRED,ViewerDownloadPolicy.decide(access("DAY"),"video",emptySet(),emptySet()))
    }
    @Test fun pendingUnacknowledgedRevokedRefundedAndForeignReceiptsStayFree() {
        val source=grant()
        for(value in listOf(source.copy(accountId="bob"),source.copy(acknowledged=false),source.copy(planId="UNKNOWN"),source.copy(periodId=""),
            source.copy(state=VerifiedPaymentState.PENDING),source.copy(state=VerifiedPaymentState.REFUNDED),source.copy(state=VerifiedPaymentState.REVOKED))) {
            assertFalse(ViewerAccessPolicy.resolve("alice",value,now).adFree)
        }
        assertFalse(ViewerAccessPolicy.resolve(null,source,now).adFree)
    }
    @Test fun expirationHasAnExactBoundaryAndFutureGrantsDoNotUnlock() {
        assertTrue(ViewerAccessPolicy.resolve("alice",grant(end=now.plusSeconds(1)),now).adFree)
        assertFalse(ViewerAccessPolicy.resolve("alice",grant(end=now),now).adFree)
        assertFalse(ViewerAccessPolicy.resolve("alice",grant().copy(startsAt=now.plusSeconds(1)),now).adFree)
    }
    @Test fun weeklyQuotaIncludesReservationsAndRetriesNeverConsumeAgain() {
        val paid=access("WEEK")
        val done=setOf("a","b","c","d");val held=setOf("e")
        assertEquals(DownloadDecision.QUOTA_REACHED,ViewerDownloadPolicy.decide(paid,"f",done,held))
        assertEquals(DownloadDecision.RESUME_RESERVED,ViewerDownloadPolicy.decide(paid,"e",done,held))
        assertEquals(DownloadDecision.REUSE_COMPLETED,ViewerDownloadPolicy.decide(paid,"a",done,held))
        assertEquals(DownloadDecision.ALLOW_NEW,ViewerDownloadPolicy.decide(paid,"f",done,emptySet()))
    }
    @Test fun monthlyQuotaIs30PerVerifiedPeriodAndDuplicatesCountOnce() {
        val paid=access();val done=(1..29).map { "video-$it" }.toSet()
        assertEquals(DownloadDecision.ALLOW_NEW,ViewerDownloadPolicy.decide(paid,"video-30",done,setOf("video-1")))
        assertEquals(DownloadDecision.QUOTA_REACHED,ViewerDownloadPolicy.decide(paid,"video-31",done,setOf("video-30")))
    }
    @Test fun offlineLeaseCannotOutlivePlanOr24Hours() {
        val short=ViewerAccessPolicy.resolve("alice",grant("WEEK",now.plusSeconds(3600)),now)
        assertEquals(now.plusSeconds(3600),ViewerDownloadPolicy.offlineUntil(short,now))
        val long=ViewerAccessPolicy.resolve("alice",grant("MONTHLY",now.plusSeconds(90*86400)),now)
        assertEquals(now.plusSeconds(86400),ViewerDownloadPolicy.offlineUntil(long,now))
        assertNull(ViewerDownloadPolicy.offlineUntil(access("DAY"),now))
    }
    @Test fun offlineLeaseRejectsRebootRollbackAndExpiry() {
        assertTrue(ViewerDownloadPolicy.validOfflineLease(1,1,1000,1500,1000))
        assertFalse(ViewerDownloadPolicy.validOfflineLease(1,2,1000,1500,1000))
        assertFalse(ViewerDownloadPolicy.validOfflineLease(-1,-1,1000,1500,1000))
        assertFalse(ViewerDownloadPolicy.validOfflineLease(1,1,1000,999,1000))
        assertFalse(ViewerDownloadPolicy.validOfflineLease(1,1,1000,2000,1000))
        assertFalse(ViewerDownloadPolicy.validOfflineLease(1,1,0,1,86_400_001))
    }
}
