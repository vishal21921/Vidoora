package com.example.vidoora.feature.membership

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.vidoora.BuildConfig
import com.example.vidoora.R
import com.example.vidoora.feature.ads.adsActivity
import com.example.vidoora.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Suppress("DEPRECATION")
@Composable
fun PaymentRecordsContent(state: PaymentsState,onRefresh: ()->Unit,onMore: ()->Unit,onRetry: (String)->Unit,onHelp: (String)->Unit) {
    val clipboard=LocalClipboardManager.current
    if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let {FormError(it)}
    if(!state.loading && !state.available && state.error==null) Text("Payment recovery isn’t configured yet. Purchases remain unavailable.",style=MaterialTheme.typography.bodyMedium)
    if(!state.loading && state.available && state.items.isEmpty()) Text("No verified receipts or pending recovery requests are recorded for this account.",style=MaterialTheme.typography.bodyMedium)
    state.items.forEach {item -> ReferenceCard {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(when(item.planId){"DAY"->"Day Pass";"WEEK"->"Week Pass";"MONTHLY"->"Monthly Ad-Free";else->"Purchase verification"},style=MaterialTheme.typography.titleMedium)
            Text(when(item.paymentState){"SUCCESS"->"Payment verified";"PENDING"->"Payment pending";"FAILED"->"Payment failed";else->"Payment not yet verified"},style=MaterialTheme.typography.bodyMedium)
            Text(when(item.activationState){"ACTIVE"->"Activation verified";"INACTIVE"->"Access inactive or expired";"REJECTED"->"Receipt could not be accepted";"PENDING"->"Waiting for payment";"RETRYING"->"Verification will retry";else->"Checking activation"},
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Reference: ${item.reference}",style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={clipboard.setText(AnnotatedString(item.reference))}) {Text("Copy ID")}
                TextButton(onClick={onHelp(item.reference)}) {Text("Get help")}
                if(item.retryable) TextButton(onClick={onRetry(item.reference)},enabled=!state.loading) {Text("Retry")}
            }
        }
    } }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick=onRefresh,enabled=!state.loading) {Text("Refresh")}
        if(state.nextCursor!=null) OutlinedButton(onClick=onMore,enabled=!state.loading) {Text("Load more")}
    }
}

/** Restore only. No caller invokes purchase(); the catalogue still has no enabled checkout. */
@Composable
internal fun RestorePurchasesPanel(account: String,model: PaymentsViewModel,config: RecoveryConfig) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val ready=BuildConfig.PLAY_BILLING_ENABLED && context.packageName=="com.vidoora.app" && config.restoreAvailable
    val gateway=remember(account,config,ready) {
        if(!ready) null else PlayBillingGateway(context,model::account,config.products.map {PlayProduct(it.planId,it.productId,it.basePlanId)},model.outbox::accept)
    }
    if(gateway==null) {
        Text("Google Play Restore will be available in the configured Vidoora billing-test build.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    DisposableEffect(gateway) {onDispose{gateway.close()}}
    LaunchedEffect(gateway,lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {gateway.connect()} }
    val status by gateway.status.collectAsState()
    OutlinedButton(onClick={gateway.restore()},enabled=status.ready && !status.busy) {Text("Restore purchases")}
    status.message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
}
