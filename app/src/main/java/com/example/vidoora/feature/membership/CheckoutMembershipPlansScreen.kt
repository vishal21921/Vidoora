package com.example.vidoora.feature.membership

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.vidoora.BuildConfig
import com.example.vidoora.feature.ads.adsActivity
import kotlinx.coroutines.delay

/** Store checkout is confined to the internal build plus an explicit server test gate. */
@Composable
internal fun CheckoutMembershipPlansScreen(membership: MembershipView,signedIn: Boolean,account: String?,
    onLogin: ()->Unit,onPayments: ()->Unit,onBack: ()->Unit,onRefresh: ()->Unit,refreshing: Boolean=false,
    model: PaymentsViewModel=hiltViewModel()) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val foreground=lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val internalBuild=BuildConfig.PLAY_CHECKOUT_ENABLED && BuildConfig.PLAY_BILLING_ENABLED && context.packageName=="com.vidoora.app"
    val observed by model.state.collectAsState()
    val state=model.owned(observed)
    val config=model.config
    LaunchedEffect(internalBuild,signedIn,account,lifecycle) {
        if(internalBuild && signedIn && account!=null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true) { model.refresh();delay(10_000) }
        }
    }
    val configured=internalBuild && signedIn && account!=null && config.restoreAvailable && config.purchasesAvailable
    val gateway=remember(account,configured,config.products) {
        if(!configured) null else PlayBillingGateway(context,model::account,
            config.products.map {PlayProduct(it.planId,it.productId,it.basePlanId)},model.outbox::accept)
    }
    DisposableEffect(gateway) {onDispose {gateway?.close()}}
    LaunchedEffect(gateway,lifecycle) {if(gateway!=null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {gateway.connect()}}
    val emptyStatus=remember { kotlinx.coroutines.flow.MutableStateFlow(BillingStatus()) }
    val emptyOffers=remember { kotlinx.coroutines.flow.MutableStateFlow<List<PlayOffer>>(emptyList()) }
    val status by (gateway?.status ?: emptyStatus).collectAsState()
    val offers by (gateway?.offers ?: emptyOffers).collectAsState()
    var requestedPlan by remember(account,gateway) {mutableStateOf<String?>(null)}
    val enabled=configured && foreground && !state.loading && state.error==null && state.available && status.canPurchase && !status.busy
    val checkout=if(!internalBuild) null else PlanCheckoutUi(offers.associate {it.planId to it.price},enabled,
        when {
            !signedIn -> "Log in to bind a purchase to your Vidoora account."
            state.error!=null -> state.error
            !configured -> "Test checkout is not configured on the server. Refresh after backend setup."
            membership.adFree -> "Your verified plan is active. Use Payments to manage access."
            else -> status.message
        },state.loading || status.busy)
    MembershipPlansScreen(membership,onBack,{
        onRefresh();gateway?.connect()
    },refreshing,checkout,{requestedPlan=it},onLogin,{gateway?.restore()},onPayments,signedIn)
    requestedPlan?.let { plan ->
        AlertDialog(onDismissRequest={requestedPlan=null},title={Text("Licence-test purchase")},
            text={Text("Use an account added to licence testing. In the Google Play sheet, select a test payment method. If a real card or UPI payment is shown, cancel. Benefits unlock only after server verification.")},
            confirmButton={TextButton(enabled=enabled && !membership.loading && !membership.adFree,onClick={
                requestedPlan=null
                val offer=gateway?.offers?.value?.singleOrNull {it.planId==plan}
                val activity=context.adsActivity()
                if(offer!=null && activity!=null) gateway?.purchase(activity,offer)
            }) {Text("Open Google Play")}},
            dismissButton={TextButton(onClick={requestedPlan=null}) {Text("Cancel")}})
    }
}
