package com.vidoora.backend.modules.membership

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Path
import java.util.Base64

/** Verified reads and separately opted-in receipt recovery. Neither switch enables checkout. */
internal object MembershipRuntime {
    internal data class Services(val entitlements: ViewerEntitlements,val recovery: PurchaseRecovery?=null,val grants: VerifiedGrantSource=VerifiedGrantSource{emptyList()})
    private fun recoveryEnabledForCheckout(config: ApplicationConfig)=config.propertyOrNull("membership.recoveryEnabled")?.getString()=="true"
    fun fromConfig(config: ApplicationConfig): ViewerEntitlements=services(config).entitlements
    fun services(config: ApplicationConfig): Services {
        val checkoutEnabled=config.propertyOrNull("membership.internalTestCheckoutEnabled")?.getString()=="true"
        val allowTests=config.propertyOrNull("membership.allowTestPurchases")?.getString()=="true"
        check(!checkoutEnabled || (recoveryEnabledForCheckout(config) && allowTests)) { "Internal checkout requires recovery and test receipts" }
        val recoveryEnabled=config.propertyOrNull("membership.recoveryEnabled")?.getString()=="true"
        if (config.propertyOrNull("membership.verifiedReadEnabled")?.getString() != "true") {
            check(!recoveryEnabled) { "Purchase recovery requires verified membership reads" }
            return Services(FreeViewerEntitlements)
        }
        try {
            fun required(name: String) = config.property("membership.$name").getString().also { require(it.isNotBlank()) }
            val cipher = PurchaseTokenCipher(Base64.getDecoder().decode(required("receiptKey")))
            val product = required("productId")
            val plans = listOf(PlayPlan(product, required("dayBasePlan"), "DAY"),
                PlayPlan(product, required("weekBasePlan"), "WEEK"), PlayPlan(product, required("monthlyBasePlan"), "MONTHLY"))
            val publisher = GooglePlayPublisher.fromPrivateFile(Path.of(required("credentialsFile")))
            val verifier = PlayPurchaseVerifier(publisher, plans,
                allowTests, requireTestPurchases=checkoutEnabled)
            val ledger=PostgresPlayPurchaseLedger(cipher)
            val source=MembershipPlaySource(verifier,ledger)
            val entitlements=VerifiedViewerEntitlements(source)
            return Services(entitlements,if(recoveryEnabled) PurchaseRecovery(PostgresPurchaseRecovery(cipher),verifier,ledger,entitlements,plans,internalTestCheckout=checkoutEnabled) else null,source)
        } catch (_: Exception) { throw PlayVerificationFailure("MEMBERSHIP_CONFIGURATION_INVALID") }
    }
}
