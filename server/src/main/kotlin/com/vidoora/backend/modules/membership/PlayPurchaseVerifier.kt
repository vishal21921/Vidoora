package com.vidoora.backend.modules.membership

import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Product/base-plan mappings come from trusted server configuration, never the purchase request. */
internal data class PlayPlan(val productId: String, val basePlanId: String, val planId: String) {
    init {
        require(Regex("[a-z0-9][a-z0-9._-]{0,149}").matches(productId))
        require(Regex("[a-z0-9][a-z0-9-]{0,62}").matches(basePlanId))
        require(planId in setOf("DAY", "WEEK", "MONTHLY"))
    }
}

/** Deliberately contains no provider response, URL, token or underlying exception. Safe for API errors. */
internal class PlayVerificationFailure(val code: String) : RuntimeException(code)

internal fun playAccountId(accountId: UUID): String = MessageDigest.getInstance("SHA-256")
    .digest("vidoora:play:account:$accountId".toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal interface PlayPublisher {
    suspend fun subscription(token: String): JsonObject
    suspend fun acknowledge(productId: String, token: String)
}

/** This receipt is not an entitlement. Durable ownership/receipt storage must precede acknowledgement. */
internal class PlayReceipt(
    val plan: PlayPlan, val accountId: UUID, val periodId: String,
    val startsAt: Instant, val expiresAt: Instant, val acknowledged: Boolean,
    val autoRenew: Boolean, val testPurchase: Boolean, internal val tokenDigest: String,
    internal val linkedTokenDigest: String? = null
) {
    override fun toString() = "PlayReceipt(redacted)"
    fun grant(): VerifiedViewerGrant = VerifiedViewerGrant(accountId.toString(), plan.planId, periodId,
        startsAt, expiresAt, VerifiedPaymentState.PURCHASED, acknowledged)
}

internal class PlayPurchaseVerifier(
    private val publisher: PlayPublisher,
    private val plans: List<PlayPlan>,
    private val allowTestPurchases: Boolean = false,
    private val requireTestPurchases: Boolean = false
) {
    init {
        require(plans.isNotEmpty())
        require(plans.map { it.productId to it.basePlanId }.distinct().size == plans.size)
    }

    suspend fun verify(account: UUID, token: String, now: Instant): PlayReceipt {
        validateToken(token)
        return parse(account, publisher.subscription(token), now, purchaseDigest(token))
    }

    // Kept separate so a caller cannot acknowledge an unpaid/unbound receipt automatically.
    suspend fun acknowledge(receipt: PlayReceipt, token: String) {
        validateToken(token)
        if (receipt.tokenDigest != purchaseDigest(token)) throw PlayVerificationFailure("PURCHASE_TOKEN_MISMATCH")
        if (!receipt.acknowledged) publisher.acknowledge(receipt.plan.productId, token)
    }

    private fun parse(account: UUID, root: JsonObject, now: Instant, digest: String): PlayReceipt {
        try {
            fun reject(code: String): Nothing = throw PlayVerificationFailure(code)
            val binding = root["externalAccountIdentifiers"]?.jsonObject?.text("obfuscatedExternalAccountId")
            if(binding==null && root.text("subscriptionState")=="SUBSCRIPTION_STATE_PENDING") reject("PAYMENT_PENDING")
            if (binding != playAccountId(account)) reject("PURCHASE_ACCOUNT_MISMATCH")
            val test = root.containsKey("testPurchase")
            if (test && !allowTestPurchases) reject("TEST_PURCHASE_DISABLED")
            if (requireTestPurchases && !test) reject("NON_TEST_PURCHASE_DISABLED")
            when (root.text("subscriptionState")) {
                "SUBSCRIPTION_STATE_PENDING" -> reject("PAYMENT_PENDING")
                "SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED" -> Unit
                else -> reject("PURCHASE_INACTIVE")
            }
            val start = Instant.parse(root.text("startTime"))
            if (start > now) reject("PURCHASE_INACTIVE")
            // Do not guess which item authorizes access for deferred replacements/bundles.
            val candidates = root["lineItems"]!!.jsonArray.map { it.jsonObject }.mapNotNull { item ->
                val plan = plans.singleOrNull { it.productId == item.text("productId") &&
                    it.basePlanId == item["offerDetails"]?.jsonObject?.text("basePlanId") } ?: return@mapNotNull null
                if (Instant.parse(item.text("expiryTime")) <= now) return@mapNotNull null
                plan to item
            }
            if (candidates.size != 1) reject("PURCHASE_PRODUCT_MISMATCH")
            val (plan, item) = candidates.single()
            val monthly = plan.planId == "MONTHLY"
            if (monthly != item.containsKey("autoRenewingPlan") || monthly == item.containsKey("prepaidPlan"))
                reject("PURCHASE_PLAN_TYPE_MISMATCH")
            val period = item.text("latestSuccessfulOrderId") ?: reject("PURCHASE_ORDER_MISSING")
            if (period.isBlank() || period.length > 256) reject("PURCHASE_ORDER_MISSING")
            val ack = when (root.text("acknowledgementState")) {
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" -> true
                "ACKNOWLEDGEMENT_STATE_PENDING" -> false
                else -> reject("PURCHASE_ACK_UNKNOWN")
            }
            val linked=root.text("linkedPurchaseToken")?.let { validateToken(it);purchaseDigest(it) }
            if(linked==digest) reject("PURCHASE_CHAIN_INVALID")
            return PlayReceipt(plan, account, period, start, Instant.parse(item.text("expiryTime")), ack,
                item["autoRenewingPlan"]?.jsonObject?.get("autoRenewEnabled")?.jsonPrimitive?.booleanOrNull == true, test, digest,linked)
        } catch (e: PlayVerificationFailure) { throw e }
        catch (_: Exception) { throw PlayVerificationFailure("PURCHASE_RESPONSE_INVALID") }
    }
}

internal fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
internal fun purchaseDigest(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun validateToken(token: String) {
    if (token.isBlank() || token.length > 4096 || token.any { it.isWhitespace() || it.isISOControl() })
        throw PlayVerificationFailure("PURCHASE_TOKEN_INVALID")
}
internal fun playPath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
