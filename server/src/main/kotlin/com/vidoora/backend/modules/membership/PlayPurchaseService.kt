package com.vidoora.backend.modules.membership

import kotlinx.serialization.Serializable
import java.time.Clock
import java.util.UUID

@Serializable
internal data class PurchaseCheck(val supportId: String, val planId: String, val expiresAt: String,
    val autoRenew: Boolean, val activation: String)

/** Retry is idempotent by token hash and account; only receipts fetched from Google are accepted. */
internal class PlayPurchaseService(
    private val verifier: PlayPurchaseVerifier, private val ledger: PlayPurchaseLedger,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun verify(account: UUID, token: String): PurchaseCheck {
        // Verify account binding before claiming a token, preventing another account from squatting it.
        val receipt = verifier.verify(account, token, clock.instant())
        val support = ledger.claimLinked(account, token,receipt.linkedTokenDigest)
        ledger.record(account, token, receipt, "VERIFIED")
        // Receipt and retry material are durable BEFORE this external side effect.
        verifier.acknowledge(receipt, token)
        val confirmed = if (receipt.acknowledged) receipt else verifier.verify(account, token, clock.instant())
        if (!confirmed.acknowledged) throw PlayVerificationFailure("PURCHASE_ACK_PENDING")
        ledger.record(account, token, confirmed, "ACKNOWLEDGED")
        // Internal verified receipt result; the recovery worker separately confirms the read-side benefit.
        return PurchaseCheck(support.toString(), confirmed.plan.planId, confirmed.expiresAt.toString(),
            confirmed.autoRenew, "ACKNOWLEDGED")
    }
}
