package com.vidoora.backend

import com.vidoora.backend.modules.membership.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.nio.ByteBuffer
import java.util.concurrent.Flow

class PlayPurchaseVerificationTest {
    @Test fun internalCheckoutRejectsRealReceiptsAndAcceptsOnlyGoogleTestReceipts() = runBlocking {
        val provider=Publisher(response(test=false))
        val verifier=PlayPurchaseVerifier(provider,plans,allowTestPurchases=true,requireTestPurchases=true)
        rejected("NON_TEST_PURCHASE_DISABLED") { verifier.verify(account,token,now) }
        assertEquals(0,provider.acknowledgements)
        provider.body=response(test=true)
        assertTrue(verifier.verify(account,token,now).testPurchase)
    }
    private val account = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val plans = listOf(PlayPlan("viewer", "day", "DAY"), PlayPlan("viewer", "week", "WEEK"), PlayPlan("viewer", "monthly", "MONTHLY"))
    private val token = "synthetic-fixture-token"
    private fun response(state: String = "ACTIVE", base: String = "monthly", ack: Boolean = true,
        binding: UUID = account, expiry: Instant = now.plusSeconds(86400), test: Boolean = false,
        prepaid: Boolean = base != "monthly", order: String = "fixture-order-1"): JsonObject = buildJsonObject {
        put("subscriptionState", "SUBSCRIPTION_STATE_$state")
        put("startTime", now.minusSeconds(60).toString())
        put("acknowledgementState", "ACKNOWLEDGEMENT_STATE_${if (ack) "ACKNOWLEDGED" else "PENDING"}")
        putJsonObject("externalAccountIdentifiers") { put("obfuscatedExternalAccountId", playAccountId(binding)) }
        if (test) putJsonObject("testPurchase") {}
        putJsonArray("lineItems") { add(buildJsonObject {
            put("productId", "viewer"); put("expiryTime", expiry.toString()); put("latestSuccessfulOrderId", order)
            putJsonObject("offerDetails") { put("basePlanId", base) }
            if (prepaid) putJsonObject("prepaidPlan") {} else putJsonObject("autoRenewingPlan") { put("autoRenewEnabled", state == "ACTIVE") }
        }) }
    }
    private class Publisher(var body: JsonObject) : PlayPublisher {
        var reads = 0; var acknowledgements = 0; var failAck = false
        val events = mutableListOf<String>()
        override suspend fun subscription(token: String): JsonObject { reads++; events += "read"; return body }
        override suspend fun acknowledge(productId: String, token: String) {
            acknowledgements++; events += "ack"
            if (failAck) throw PlayVerificationFailure("PLAY_UNAVAILABLE")
            body = JsonObject(body + ("acknowledgementState" to JsonPrimitive("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED")))
        }
    }
    private suspend fun rejected(code: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") }
        catch (e: PlayVerificationFailure) { assertEquals(code, e.code); assertFalse(e.toString().contains(token)); assertNull(e.cause) }
    }
    @Test fun monthlyUsesStoreExpiryAndRealOrderNotThirtyDayMath() = runBlocking {
        val expiry = now.plusSeconds(31 * 86400)
        val receipt = PlayPurchaseVerifier(Publisher(response(expiry = expiry)), plans).verify(account, token, now)
        assertEquals(expiry, receipt.expiresAt); assertEquals("fixture-order-1", receipt.periodId)
        assertTrue(ViewerAccessPolicy.resolve(account.toString(), receipt.grant(), now).adFree)
        assertEquals("PlayReceipt(redacted)", receipt.toString())
    }
    @Test fun prepaidPassesUseStorePeriodAndNeverAutoRenew() = runBlocking {
        for (plan in listOf("day", "week")) {
            val receipt = PlayPurchaseVerifier(Publisher(response(base = plan)), plans).verify(account, token, now)
            assertEquals(plan.uppercase(), receipt.plan.planId); assertFalse(receipt.autoRenew)
        }
    }
    @Test fun pendingHoldPausedExpiredRevokedAndUnknownStatesCannotGrant() = runBlocking {
        for (state in listOf("PENDING", "ON_HOLD", "PAUSED", "EXPIRED", "PENDING_PURCHASE_CANCELED", "UNSPECIFIED", "REVOKED")) {
            val pub = Publisher(response(state = state))
            rejected(if (state == "PENDING") "PAYMENT_PENDING" else "PURCHASE_INACTIVE") {
                PlayPurchaseVerifier(pub, plans).verify(account, token, now)
            }
            assertEquals(0, pub.acknowledgements)
        }
    }
    @Test fun cancellationKeepsPaidAccessUntilExpiryAndGraceRemainsEligible() = runBlocking {
        for (state in listOf("CANCELED", "IN_GRACE_PERIOD")) {
            val receipt = PlayPurchaseVerifier(Publisher(response(state = state)), plans).verify(account, token, now)
            assertTrue(ViewerAccessPolicy.resolve(account.toString(), receipt.grant(), now).adFree)
        }
        rejected("PURCHASE_PRODUCT_MISMATCH") { PlayPurchaseVerifier(Publisher(response(expiry = now)), plans).verify(account, token, now) }
    }
    @Test fun foreignAndMissingAccountBindingsCannotBeRestored() = runBlocking {
        rejected("PURCHASE_ACCOUNT_MISMATCH") { PlayPurchaseVerifier(Publisher(response(binding = UUID.randomUUID())), plans).verify(account, token, now) }
        rejected("PURCHASE_ACCOUNT_MISMATCH") { PlayPurchaseVerifier(Publisher(JsonObject(response() - "externalAccountIdentifiers")), plans).verify(account, token, now) }
    }
    @Test fun wrongProductBasePlanAndPlanTypeAreRejected() = runBlocking {
        rejected("PURCHASE_PRODUCT_MISMATCH") { PlayPurchaseVerifier(Publisher(response(base = "unknown")), plans).verify(account, token, now) }
        rejected("PURCHASE_PLAN_TYPE_MISMATCH") { PlayPurchaseVerifier(Publisher(response(base = "week", prepaid = false)), plans).verify(account, token, now) }
        rejected("PURCHASE_PLAN_TYPE_MISMATCH") { PlayPurchaseVerifier(Publisher(response(prepaid = true)), plans).verify(account, token, now) }
    }
    @Test fun testPurchasesRequireExplicitServerOptIn() = runBlocking {
        val pub = Publisher(response(test = true))
        rejected("TEST_PURCHASE_DISABLED") { PlayPurchaseVerifier(pub, plans).verify(account, token, now) }
        assertTrue(PlayPurchaseVerifier(pub, plans, true).verify(account, token, now).testPurchase)
    }
    @Test fun malformedAndAmbiguousResponsesFailClosed() = runBlocking {
        for (body in listOf(JsonObject(emptyMap()), JsonObject(response() + ("startTime" to JsonPrimitive("invalid"))),
            JsonObject(response() + ("acknowledgementState" to JsonPrimitive("UNKNOWN"))))) {
            try { PlayPurchaseVerifier(Publisher(body), plans).verify(account, token, now); fail() }
            catch (_: PlayVerificationFailure) { }
        }
        val root = response()
        val two = JsonObject(root + ("lineItems" to JsonArray(root["lineItems"]!!.jsonArray + root["lineItems"]!!.jsonArray)))
        rejected("PURCHASE_PRODUCT_MISMATCH") { PlayPurchaseVerifier(Publisher(two), plans).verify(account, token, now) }
    }
    @Test fun validationNeverAcknowledgesAndUnacknowledgedReceiptIsNotPremium() = runBlocking {
        val pub = Publisher(response(ack = false)); val verifier = PlayPurchaseVerifier(pub, plans)
        val receipt = verifier.verify(account, token, now)
        assertFalse(ViewerAccessPolicy.resolve(account.toString(), receipt.grant(), now).adFree)
        assertEquals(0, pub.acknowledgements)
        rejected("PURCHASE_TOKEN_MISMATCH") { verifier.acknowledge(receipt, "different-fixture-token") }
        assertEquals(0, pub.acknowledgements)
    }
    @Test fun invalidTokensNeverReachGoogle() = runBlocking {
        val pub = Publisher(response())
        for (bad in listOf("", " ", "bad\nvalue", "x".repeat(4097))) rejected("PURCHASE_TOKEN_INVALID") {
            PlayPurchaseVerifier(pub, plans).verify(account, bad, now)
        }
        assertEquals(0, pub.reads)
    }
    @Test fun encryptedTokensAreRandomizedAndRejectTamperingOrRowSwaps() {
        val cipher = PurchaseTokenCipher(ByteArray(32) { it.toByte() })
        val a = cipher.encrypt(token); val b = cipher.encrypt(token)
        assertNotEquals(a, b); assertFalse(a.contains(token)); assertEquals(token, cipher.decrypt(a, purchaseDigest(token)))
        for (pair in listOf(a to purchaseDigest("other"), ("!" + a.drop(1)) to purchaseDigest(token))) {
            try { cipher.decrypt(pair.first, pair.second); fail() } catch (e: PlayVerificationFailure) { assertEquals("PURCHASE_STORAGE_INVALID", e.code) }
        }
    }
    @Test fun journalCommitsBeforeAcknowledgementAndRetryReusesSupportReference() = runBlocking {
        val pub = Publisher(response(ack = false)); val support = UUID.randomUUID()
        val ledger = object : PlayPurchaseLedger {
            override suspend fun claim(account: UUID, token: String): UUID { pub.events += "claim"; return support }
            override suspend fun record(account: UUID, token: String, receipt: PlayReceipt?, state: String) { pub.events += state }
            override suspend fun tokens(account: UUID) = listOf(token)
        }
        val service = PlayPurchaseService(PlayPurchaseVerifier(pub, plans), ledger, Clock.fixed(now, ZoneOffset.UTC))
        pub.failAck = true
        rejected("PLAY_UNAVAILABLE") { service.verify(account, token) }
        assertEquals(listOf("read", "claim", "VERIFIED", "ack"), pub.events)
        pub.failAck = false; pub.events.clear()
        val check = service.verify(account, token)
        assertEquals(support.toString(), check.supportId)
        assertEquals(listOf("read", "claim", "VERIFIED", "ack", "read", "ACKNOWLEDGED"), pub.events)
        assertEquals("ACKNOWLEDGED", check.activation)
        pub.events.clear(); assertEquals(support.toString(), service.verify(account, token).supportId)
        assertFalse(pub.events.contains("ack"))
    }
    @Test fun persistenceFailureNeverAcknowledgesPayment() = runBlocking {
        val pub = Publisher(response(ack = false))
        val ledger = object : PlayPurchaseLedger {
            override suspend fun claim(account: UUID, token: String): UUID = throw PlayVerificationFailure("STORAGE_UNAVAILABLE")
            override suspend fun record(account: UUID, token: String, receipt: PlayReceipt?, state: String) { fail() }
            override suspend fun tokens(account: UUID) = emptyList<String>()
        }
        rejected("STORAGE_UNAVAILABLE") { PlayPurchaseService(PlayPurchaseVerifier(pub, plans), ledger,
            Clock.fixed(now, ZoneOffset.UTC)).verify(account, token) }
        assertEquals(0, pub.acknowledgements)
    }
    @Test fun providerBodyLimitCancelsBeforeAllocatingOversizedContent() {
        var cancelled = false
        val subscription = object : Flow.Subscription {
            override fun request(n: Long) {}
            override fun cancel() { cancelled = true }
        }
        val body = LimitedPlayBody(8); body.onSubscribe(subscription)
        body.onNext(listOf(ByteBuffer.wrap(ByteArray(9))))
        assertTrue(cancelled); assertTrue(body.body.isCompletedExceptionally)
        val small = LimitedPlayBody(8); small.onSubscribe(subscription)
        small.onNext(listOf(ByteBuffer.wrap("fixture".toByteArray()))); small.onComplete()
        assertEquals("fixture", String(small.body.get()))
    }
}
