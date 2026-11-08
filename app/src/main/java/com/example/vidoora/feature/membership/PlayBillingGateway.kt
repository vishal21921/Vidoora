package com.example.vidoora.feature.membership

import android.app.Activity
import android.content.Context
import androidx.annotation.MainThread
import com.android.billingclient.api.*
import com.example.vidoora.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

internal data class PlayProduct(val planId: String, val productId: String, val basePlanId: String)
internal data class BillingStatus(val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val canPurchase: Boolean=false)

/** A store offer, not a paid entitlement. Display price comes from Google Play, not a hardcoded rupee string. */
internal class PlayOffer internal constructor(
    val planId: String, val price: String, val billingPeriod: String,
    internal val accountId: String, internal val details: ProductDetails, internal val token: String
) {
    override fun toString() = "PlayOffer($planId)"
}

/**
 * One instance per signed-in billing screen/session. No local premium flags, consumption or acknowledgement.
 * The server callback must durably bind and verify receipts before acknowledging/granting access.
 * Not attached to navigation until that backend and real Play product configuration are ready.
 */
@MainThread
internal class PlayBillingGateway(
    context: Context,
    private val currentAccount: () -> String?,
    private val products: List<PlayProduct>,
    private val submitToServer: suspend (accountId: String, token: String) -> Unit
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(BillingStatus())
    val status = mutable.asStateFlow()
    private val mutableOffers = MutableStateFlow<List<PlayOffer>>(emptyList())
    val offers = mutableOffers.asStateFlow()
    private var closed = false
    private var checkoutInProgress=false
    private var purchasesChecked=false
    private var ownsPurchase=false
    private var connecting=false
    private var loadingOffers=false
    private var checkingPurchases=false
    private var sessionAccount: String? = null
    private val inFlight = mutableSetOf<String>()
    private val enabled = BuildConfig.PLAY_BILLING_ENABLED && context.packageName == "com.vidoora.app"
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            if (closed) return@setListener
            checkoutInProgress=false
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    if(purchases.isNullOrEmpty()) update("No purchase returned. Tap Restore to check.")
                    else { ownsPurchase=true;purchases.forEach(::receive) }
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> update("Purchase cancelled")
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
                else -> update("Google Play is unavailable. Try again.")
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection()
        .build()

    fun connect() {
        if (closed || !enabled) { update("Purchases are not enabled in this build"); return }
        val account = currentAccount()?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        if (account == null) { update("Log in to purchase or restore a plan"); return }
        if(checkoutInProgress || connecting || loadingOffers || checkingPurchases || inFlight.isNotEmpty()) return
        sessionAccount = account
        purchasesChecked=false
        mutable.value = BillingStatus(busy = true)
        if (client.isReady) { loadOffers(); restore(); return }
        connecting=true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting=false
                if (!sameAccount()) { invalidate(); return }
                if (result.responseCode == BillingClient.BillingResponseCode.OK) { loadOffers(); restore() }
                else update("Google Play is unavailable. Try again.")
            }
            override fun onBillingServiceDisconnected() {
                connecting=false;purchasesChecked=false
                if (!closed) mutable.value = BillingStatus(message = "Google Play disconnected. Try again.")
            }
        })
    }

    private fun loadOffers() {
        if (!sameAccount()) { invalidate(); return }
        mutableOffers.value=emptyList()
        val params = QueryProductDetailsParams.newBuilder().setProductList(products.map { it.productId }.distinct().map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        if (products.isEmpty()) { update("Plans are not configured yet"); return }
        loadingOffers=true
        client.queryProductDetailsAsync(params) { result, response ->
            loadingOffers=false
            if (!sameAccount()) { invalidate(); return@queryProductDetailsAsync }
            if (result.responseCode != BillingClient.BillingResponseCode.OK) { update("Plans could not be loaded"); return@queryProductDetailsAsync }
            mutableOffers.value = response.productDetailsList.flatMap { details ->
                details.subscriptionOfferDetails.orEmpty().mapNotNull { offer ->
                    val configured = products.singleOrNull { it.productId == details.productId && it.basePlanId == offer.basePlanId }
                        ?: return@mapNotNull null
                    // No trials/discount offers until their commercial terms are explicitly supported.
                    if (offer.offerId != null) return@mapNotNull null
                    val phase = offer.pricingPhases.pricingPhaseList.singleOrNull() ?: return@mapNotNull null
                    if (!approvedPlayPrice(configured.planId, phase.priceCurrencyCode, phase.priceAmountMicros,
                            phase.billingPeriod, phase.recurrenceMode)) return@mapNotNull null
                    PlayOffer(configured.planId, phase.formattedPrice, phase.billingPeriod,
                        checkNotNull(sessionAccount), details, offer.offerToken)
                }
            }
            update(if (mutableOffers.value.isEmpty()) "Plans are not available in Google Play yet" else null)
        }
    }

    fun purchase(activity: Activity, offer: PlayOffer) {
        if (!sameAccount() || offer.accountId != sessionAccount) { invalidate(); return }
        if (!BuildConfig.PLAY_CHECKOUT_ENABLED || !enabled || !purchasesChecked || ownsPurchase || !client.isReady || mutable.value.busy || offer !in mutableOffers.value || activity.isFinishing || activity.isDestroyed) return
        checkoutInProgress=true
        mutable.value = BillingStatus(ready = true, busy = true)
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.details).setOfferToken(offer.token).build()
        val params = BillingFlowParams.newBuilder().setObfuscatedAccountId(playAccountBinding(checkNotNull(sessionAccount)))
            .setProductDetailsParamsList(listOf(item)).build()
        try {
            if (client.launchBillingFlow(activity, params).responseCode != BillingClient.BillingResponseCode.OK) {
                checkoutInProgress=false;update("Purchase could not be started. Try again.")
            }
        } catch (_: Exception) { checkoutInProgress=false;update("Purchase could not be started. Try again.") }
    }

    /** Invoke on foreground/explicit Restore; also discovers pending payments that completed while away. */
    fun restore() {
        if (!sameAccount() || !enabled || !client.isReady || checkoutInProgress) return
        if(checkingPurchases) return
        checkingPurchases=true
        purchasesChecked=false
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            checkingPurchases=false
            if (!sameAccount()) { invalidate(); return@queryPurchasesAsync }
            if (result.responseCode != BillingClient.BillingResponseCode.OK) { update("Restore failed. Try again."); return@queryPurchasesAsync }
            purchasesChecked=true
            ownsPurchase=purchases.any { it.products.any { id -> products.any {p -> p.productId==id} } }
            if (purchases.isEmpty()) update("No Google Play purchases found")
            else if(!ownsPurchase) update(null)
            purchases.forEach(::receive)
        }
    }

    private fun receive(purchase: Purchase) {
        if (!sameAccount()) { invalidate(); return }
        val account = checkNotNull(sessionAccount)
        val binding=purchase.accountIdentifiers?.obfuscatedAccountId
        if ((binding!=null && binding!=playAccountBinding(account)) ||
            (binding==null && purchase.purchaseState!=Purchase.PurchaseState.PENDING)) {
            update("This purchase belongs to a different Vidoora account"); return
        }
        if (purchase.purchaseState !in listOf(Purchase.PurchaseState.PENDING,Purchase.PurchaseState.PURCHASED) || purchase.products.none { id -> products.any { it.productId == id } }) return
        val token = purchase.purchaseToken
        if (!inFlight.add(token)) return
        mutable.value = BillingStatus(ready = true, busy = true, message = if(purchase.purchaseState==Purchase.PurchaseState.PENDING) "Payment pending. Your plan is not active yet." else "Saving receipt for verification")
        scope.launch {
            var saved=false
            try {
                // Never infer entitlement from Purchase.purchaseState, isAcknowledged or callback success.
                submitToServer(account, token)
                saved=true
                if (sameAccount()) update("Receipt saved. Check Payments for verification status.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (sameAccount()) update("Verification could not finish. Tap Restore to retry.") }
            finally { inFlight.remove(token);if(sameAccount()) update(if(saved) "Receipt saved. Open Payments for verification; an existing purchase prevents another checkout." else "Receipt could not be saved. Tap Restore to retry.") }
        }
    }

    private fun sameAccount() = !closed && sessionAccount != null && sessionAccount == currentAccount()
    private fun update(message: String?) { if (!closed) mutable.value = BillingStatus(ready = enabled && client.isReady,
        busy=checkoutInProgress || loadingOffers || checkingPurchases || inFlight.isNotEmpty(), message = message,
        canPurchase=BuildConfig.PLAY_CHECKOUT_ENABLED && enabled && client.isReady && purchasesChecked && !ownsPurchase && !checkoutInProgress && !loadingOffers && !checkingPurchases && inFlight.isEmpty()) }
    private fun invalidate() { mutableOffers.value = emptyList(); sessionAccount = null; update("Account changed. Reopen plans.") }
    override fun close() { closed = true; sessionAccount = null; mutableOffers.value = emptyList(); inFlight.clear(); scope.cancel(); client.endConnection() }
}

internal fun playAccountBinding(account: String): String {
    val canonical = UUID.fromString(account).toString()
    return MessageDigest.getInstance("SHA-256").digest("vidoora:play:account:$canonical".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Initial launch is the approved INR catalogue. Other currencies/offers need an explicit pricing decision. */
internal fun approvedPlayPrice(planId: String, currency: String, micros: Long, period: String, recurrence: Int): Boolean {
    if (currency != "INR") return false
    return when (planId) {
        "DAY" -> micros == 13_000_000L && period == "P1D" && recurrence == ProductDetails.RecurrenceMode.NON_RECURRING
        "WEEK" -> micros == 39_000_000L && period in setOf("P1W", "P7D") && recurrence == ProductDetails.RecurrenceMode.NON_RECURRING
        "MONTHLY" -> micros == 99_000_000L && period == "P1M" && recurrence == ProductDetails.RecurrenceMode.INFINITE_RECURRING
        else -> false
    }
}
