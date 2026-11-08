package com.vidoora.backend.modules.membership

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Serializable
data class PaymentRecord(val reference: String,val paymentState: String,val activationState: String,
    val planId: String?=null,val expiresAt: String?=null,val createdAt: String,val updatedAt: String,val retryable: Boolean=false)
@Serializable data class PaymentPage(val available: Boolean,val items: List<PaymentRecord>,val nextCursor: String?=null)
@Serializable data class BillingProduct(val planId: String,val productId: String,val basePlanId: String)
@Serializable data class BillingRecoveryConfig(val restoreAvailable: Boolean=false,val purchasesAvailable: Boolean=false,val products: List<BillingProduct> = emptyList())
@Serializable class SubmitPurchase(val token: String) { override fun toString()="SubmitPurchase(redacted)" }
class PaymentNotFound : RuntimeException()
class PaymentRateLimited : RuntimeException()

internal class RecoveryClaim(val id: UUID,val account: UUID,val token: String,val lease: UUID,
    val tries: Int,val alreadyVerified: Boolean) {
    override fun toString()="RecoveryClaim(redacted)"
}
internal data class RecoveryResult(val payment: String,val activation: String,val receipt: PlayReceipt?=null,
    val error: String?=null,val next: Instant?=null)
internal interface PurchaseRecoveryStore {
    suspend fun enqueue(account: UUID,token: String,now: Instant): PaymentRecord
    suspend fun claim(now: Instant): RecoveryClaim?
    suspend fun finish(claim: RecoveryClaim,result: RecoveryResult,now: Instant): Boolean
    suspend fun page(account: UUID,cursor: UUID?): PaymentPage
    suspend fun retry(account: UUID,reference: UUID,now: Instant): PaymentRecord
}

/** Accepted receipts survive device disconnects and API/worker restarts. No client paid claims are trusted. */
class PurchaseRecovery internal constructor(private val store: PurchaseRecoveryStore,
    private val verifier: PlayPurchaseVerifier,private val ledger: PlayPurchaseLedger,
    private val entitlements: VerifiedViewerEntitlements,private val plans: List<PlayPlan>,
    private val clock: Clock=Clock.systemUTC(),private val internalTestCheckout: Boolean=false) {
    fun config()=BillingRecoveryConfig(true,internalTestCheckout,plans.map { BillingProduct(it.planId,it.productId,it.basePlanId) })
    suspend fun submit(account: UUID,token: String): PaymentRecord { validateToken(token);return store.enqueue(account,token,clock.instant()) }
    suspend fun page(account: UUID,cursor: UUID?)=store.page(account,cursor)
    suspend fun retry(account: UUID,reference: UUID)=store.retry(account,reference,clock.instant())
    suspend fun runOne(): Boolean {
        val claim=store.claim(clock.instant()) ?: return false
        try {
            val result=withTimeoutOrNull(90_000) { process(claim) }
                ?: RecoveryResult(if(claim.alreadyVerified) "SUCCESS" else "UNKNOWN","RETRYING",error="VERIFICATION_UNAVAILABLE",next=backoff(claim))
            store.finish(claim,result,clock.instant())
        } catch(e: CancellationException) { throw e } // Lease expiry recovers an interrupted worker.
        catch (_: Exception) {
            store.finish(claim,RecoveryResult(if(claim.alreadyVerified) "SUCCESS" else "UNKNOWN","RETRYING",
                error="VERIFICATION_UNAVAILABLE",next=backoff(claim)),clock.instant())
        }
        return true
    }
    private suspend fun process(claim: RecoveryClaim): RecoveryResult {
        var verified: PlayReceipt?=null
        try {
            val receipt=verifier.verify(claim.account,claim.token,clock.instant())
            ledger.claimLinked(claim.account,claim.token,receipt.linkedTokenDigest)
            ledger.record(claim.account,claim.token,receipt,"VERIFIED")
            verifier.acknowledge(receipt,claim.token)
            val confirmed=if(receipt.acknowledged) receipt else verifier.verify(claim.account,claim.token,clock.instant())
            if(!confirmed.acknowledged) throw PlayVerificationFailure("PURCHASE_ACK_PENDING")
            ledger.record(claim.account,claim.token,confirmed,"ACKNOWLEDGED")
            verified=confirmed
            entitlements.invalidate(claim.account)
            val access=entitlements.resolve(claim.account.toString())
            if(!access.adFree) return RecoveryResult("SUCCESS","RETRYING",confirmed,"ACTIVATION_PENDING",backoff(claim))
            return RecoveryResult("SUCCESS","ACTIVE",confirmed,next=clock.instant().plusSeconds(900))
        } catch(e: CancellationException) { throw e }
        catch(e: PlayVerificationFailure) {
            val paid=if(verified!=null || claim.alreadyVerified) "SUCCESS" else "UNKNOWN"
            return when(e.code) {
                "PAYMENT_PENDING" -> RecoveryResult(if(claim.alreadyVerified) "SUCCESS" else "PENDING","PENDING",next=clock.instant().plusSeconds(900))
                "PURCHASE_ACCOUNT_MISMATCH","PURCHASE_PRODUCT_MISMATCH","PURCHASE_PLAN_TYPE_MISMATCH","TEST_PURCHASE_DISABLED",
                "PURCHASE_SUPERSEDED","PURCHASE_CHAIN_INVALID","NON_TEST_PURCHASE_DISABLED" -> {
                    entitlements.invalidate(claim.account);RecoveryResult(paid,"REJECTED",error=e.code)
                }
                "PURCHASE_INACTIVE" -> { entitlements.invalidate(claim.account);RecoveryResult(paid,"INACTIVE",error=e.code,next=if(claim.tries<=90) clock.instant().plusSeconds(86400) else null) }
                else -> RecoveryResult(paid,"RETRYING",verified,"VERIFICATION_UNAVAILABLE",backoff(claim))
            }
        }
    }
    private fun backoff(claim: RecoveryClaim)=clock.instant().plusSeconds(minOf(900,30L shl minOf(claim.tries.coerceAtLeast(1)-1,5)))
}
