package com.vidoora.backend

import com.vidoora.backend.modules.membership.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.UUID

class PurchaseRecoveryTest {
    private val account=UUID.randomUUID();private val now=Instant.parse("2026-10-30T12:00:00Z")
    private class ClockFixture(var value: Instant): Clock() {override fun instant()=value;override fun getZone()=ZoneOffset.UTC;override fun withZone(z: ZoneId)=this}
    private class Queue(private val account: UUID,private val now: Instant): PurchaseRecoveryStore {
        val ref=UUID.randomUUID();var saved=false;var claimed=false;var tries=0;var result: RecoveryResult?=null;var token=""
        override suspend fun enqueue(account: UUID,token: String,now: Instant): PaymentRecord {require(account==this.account);saved=true;this.token=token;return item()}
        private fun item()=PaymentRecord(ref.toString(),result?.payment ?: "UNKNOWN",result?.activation ?: "RECEIVED",createdAt=now.toString(),updatedAt=now.toString())
        override suspend fun claim(now: Instant): RecoveryClaim? {
            if(!saved || claimed || result?.next?.let{it>now}==true) return null
            claimed=true;tries++;return RecoveryClaim(ref,account,token,UUID.randomUUID(),tries,result?.payment=="SUCCESS")
        }
        override suspend fun finish(claim: RecoveryClaim,result: RecoveryResult,now: Instant): Boolean {this.result=result;claimed=false;return true}
        override suspend fun page(account: UUID,cursor: UUID?)=PaymentPage(true,if(account==this.account && saved) listOf(item()) else emptyList())
        override suspend fun retry(account: UUID,reference: UUID,now: Instant)=item()
    }
    private class Publisher(private val account: UUID,private val now: Instant): PlayPublisher {
        var state="ACTIVE";var ack=false;var fail=false;var acknowledgements=0;var reads=0
        override suspend fun subscription(token: String): JsonObject {
            reads++;if(fail) throw PlayVerificationFailure("PLAY_UNAVAILABLE")
            return buildJsonObject {
                put("subscriptionState","SUBSCRIPTION_STATE_$state");put("startTime",now.minusSeconds(10).toString())
                put("acknowledgementState","ACKNOWLEDGEMENT_STATE_${if(ack) "ACKNOWLEDGED" else "PENDING"}")
                putJsonObject("externalAccountIdentifiers"){put("obfuscatedExternalAccountId",playAccountId(account))}
                putJsonArray("lineItems"){add(buildJsonObject {
                    put("productId","viewer");put("expiryTime",now.plusSeconds(86400).toString());put("latestSuccessfulOrderId","fixture-order")
                    putJsonObject("offerDetails"){put("basePlanId","monthly")};putJsonObject("autoRenewingPlan"){put("autoRenewEnabled",true)}
                })}
            }
        }
        override suspend fun acknowledge(productId: String,token: String) {acknowledgements++;ack=true}
    }
    private class Ledger(private val provider: Publisher): PlayPurchaseLedger {
        val writes=mutableListOf<String>();var receipt: PlayReceipt?=null
        override suspend fun claim(account: UUID,token: String): UUID {writes+="CLAIM";assertFalse(provider.ack);return UUID.randomUUID()}
        override suspend fun record(account: UUID,token: String,receipt: PlayReceipt?,state: String) {writes+=state;if(state=="ACKNOWLEDGED")this.receipt=receipt}
        override suspend fun tokens(account: UUID)=emptyList<String>()
    }
    @Test fun acceptanceIsDurableBeforeGoogleAndRestartedWorkerActivatesOnlyConfirmedReceipts()=runBlocking {
        val queue=Queue(account,now);val publisher=Publisher(account,now);val ledger=Ledger(publisher);val clock=ClockFixture(now)
        val plans=listOf(PlayPlan("viewer","monthly","MONTHLY"));val verifier=PlayPurchaseVerifier(publisher,plans)
        val entitlements=VerifiedViewerEntitlements(VerifiedGrantSource{ledger.receipt?.let{listOf(it.grant())} ?: emptyList()},clock)
        val api=PurchaseRecovery(queue,verifier,ledger,entitlements,plans,clock)
        val accepted=api.submit(account,"fixture-token");assertEquals(0,publisher.reads);assertTrue(queue.saved)
        assertFalse(entitlements.resolve(account.toString()).adFree)
        val restarted=PurchaseRecovery(queue,verifier,ledger,entitlements,plans,clock)
        assertTrue(restarted.runOne());assertEquals(listOf("CLAIM","VERIFIED","ACKNOWLEDGED"),ledger.writes)
        assertEquals("SUCCESS",queue.result!!.payment);assertEquals("ACTIVE",queue.result!!.activation)
        assertEquals(1,publisher.acknowledgements);assertTrue(entitlements.resolve(account.toString()).adFree)
        assertEquals(accepted.reference,api.submit(account,"fixture-token").reference)
        assertFalse(api.config().purchasesAvailable)
        val testCheckout=PurchaseRecovery(queue,verifier,ledger,entitlements,plans,clock,internalTestCheckout=true)
        assertTrue(testCheckout.config().purchasesAvailable)
        assertTrue(testCheckout.config().restoreAvailable)
    }
    @Test fun pendingAndNetworkFailureNeverGrantAndRecoveryDoesNotNeedDeviceToResubmit()=runBlocking {
        val queue=Queue(account,now);val publisher=Publisher(account,now);val ledger=Ledger(publisher);val clock=ClockFixture(now)
        val plans=listOf(PlayPlan("viewer","monthly","MONTHLY"));val verifier=PlayPurchaseVerifier(publisher,plans)
        val entitlements=VerifiedViewerEntitlements(VerifiedGrantSource{ledger.receipt?.let{listOf(it.grant())} ?: emptyList()},clock)
        val recovery=PurchaseRecovery(queue,verifier,ledger,entitlements,plans,clock)
        recovery.submit(account,"fixture-token");publisher.state="PENDING";recovery.runOne()
        assertEquals("PENDING",queue.result!!.payment);assertEquals(0,publisher.acknowledgements);assertTrue(ledger.writes.isEmpty())
        clock.value=now.plusSeconds(901);publisher.state="ACTIVE";publisher.fail=true;recovery.runOne()
        assertEquals("RETRYING",queue.result!!.activation);assertEquals(0,publisher.acknowledgements)
        clock.value=now.plusSeconds(1801);publisher.fail=false;recovery.runOne()
        assertEquals("SUCCESS",queue.result!!.payment);assertEquals("ACTIVE",queue.result!!.activation)
    }
    @Test fun foreignBindingIsRejectedWithoutClaimOrAcknowledgement()=runBlocking {
        val queue=Queue(account,now);val provider=Publisher(UUID.randomUUID(),now);val ledger=Ledger(provider)
        val plans=listOf(PlayPlan("viewer","monthly","MONTHLY"));val clock=ClockFixture(now)
        val resolver=VerifiedViewerEntitlements(VerifiedGrantSource{emptyList()},clock)
        val recovery=PurchaseRecovery(queue,PlayPurchaseVerifier(provider,plans),ledger,resolver,plans,clock)
        recovery.submit(account,"fixture-token");recovery.runOne()
        assertEquals("REJECTED",queue.result!!.activation);assertEquals(0,provider.acknowledgements);assertTrue(ledger.writes.isEmpty())
        assertFalse(resolver.resolve(account.toString()).adFree)
    }
    @Test fun inactiveReceiptsNeverGrantAndGetABoundedDailyRecoveryAttempt()=runBlocking {
        val queue=Queue(account,now);val provider=Publisher(account,now);provider.state="ON_HOLD"
        val ledger=Ledger(provider);val plans=listOf(PlayPlan("viewer","monthly","MONTHLY"));val clock=ClockFixture(now)
        val resolver=VerifiedViewerEntitlements(VerifiedGrantSource{emptyList()},clock)
        val recovery=PurchaseRecovery(queue,PlayPurchaseVerifier(provider,plans),ledger,resolver,plans,clock)
        recovery.submit(account,"fixture-token");recovery.runOne()
        assertEquals("INACTIVE",queue.result!!.activation);assertEquals(now.plusSeconds(86400),queue.result!!.next)
        assertEquals(0,provider.acknowledgements);assertFalse(resolver.resolve(account.toString()).adFree)
    }
    @Test fun redactedObjectsNeverExposeRawTokens() {
        assertFalse(SubmitPurchase("secret-fixture").toString().contains("secret-fixture"))
        assertFalse(RecoveryClaim(UUID.randomUUID(),account,"secret-fixture",UUID.randomUUID(),1,false).toString().contains("secret-fixture"))
    }
}
