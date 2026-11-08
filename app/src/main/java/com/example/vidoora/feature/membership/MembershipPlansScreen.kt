package com.example.vidoora.feature.membership

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Approved catalogue preview only. Real checkout must display Google Play's verified localized offers. */
data class PlanCheckoutUi(val prices: Map<String,String> = emptyMap(),val enabled: Boolean=false,
    val message: String?=null,val busy: Boolean=false)

private data class PlanPreview(val id: String, val title: String, val price: String, val validity: String,
    val payment: String, val downloads: String)
private val planPreviews=listOf(
    PlanPreview("DAY","Day Pass","₹13","24 hours","One-time","No downloads"),
    PlanPreview("WEEK","Week Pass","₹39","7 days","One-time","5 downloads / pass"),
    PlanPreview("MONTHLY","Monthly Ad-Free","₹99","Per month","Auto-renewing","30 downloads / billing period")
)

private fun planName(id: String)=if (id == "ADMIN") "Admin access" else planPreviews.firstOrNull { it.id==id }?.title ?: "Free viewing"

@Composable
fun MembershipEntryCard(membership: MembershipView=MembershipView(),onClick: ()->Unit,
    modifier: Modifier=Modifier,enabled: Boolean=true) {
    Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(12.dp),
        color=MaterialTheme.colorScheme.surfaceContainerLow,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.4f)),
        modifier=modifier.fillMaxWidth().testTag("membership_entry")) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer,
                modifier=Modifier.size(42.dp)) {
                Box(contentAlignment=Alignment.Center) { UiIcon(R.drawable.ic_ui_play) }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(membership.adFree && !membership.loading) planName(membership.planId) else "Subscription plans",
                    style=MaterialTheme.typography.titleSmall)
                Text(if(membership.adFree && !membership.loading) "View your access" else "Ad-free passes from ₹13 · Preview",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UiIcon(R.drawable.ic_ui_chevron,modifier=Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MembershipPlansScreen(membership: MembershipView,onBack: ()->Unit,onRefresh: ()->Unit,
    refreshing: Boolean=false,checkout: PlanCheckoutUi?=null,onPurchase: (String)->Unit={},
    onLogin: ()->Unit={},onRestore: ()->Unit={},onPayments: ()->Unit={},signedIn: Boolean=true) {
    Scaffold(topBar={ ReferenceHeader("Subscription plans",onBack,actions={
        IconButton(onClick=onRefresh,enabled=!refreshing && !membership.loading) {
            UiIcon(R.drawable.ic_ui_refresh,"Refresh access")
        }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .readableWidth(640.dp).verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("membership_plans"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ReferenceCard {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("YOUR VIEWING ACCESS",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(membership.loading) "Checking access…" else planName(membership.planId),
                        style=MaterialTheme.typography.titleLarge)
                    if(membership.loading || refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(!membership.loading) {
                        Text(if(membership.adFree) "Ad-free · Available higher qualities" else "With ads · Up to 480p",
                            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(membership.planId == "ADMIN") Text("Ad-free · Unlimited downloads",style=MaterialTheme.typography.bodySmall)
                        if(membership.adFree && membership.planId != "ADMIN") membership.expiresAt?.let { timestamp ->
                            val formatted=runCatching { DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
                                .withZone(ZoneId.systemDefault()).format(Instant.parse(timestamp)) }.getOrNull()
                            if(formatted!=null) Text("Valid until $formatted",style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(if(checkout==null) "Plans preview" else "Internal billing test",style=MaterialTheme.typography.titleSmall)
                    Text(if(checkout==null) "Payments and downloads aren’t available yet." else
                        "Use a licence-test account. Continue only if Google Play shows a test payment method. Access starts after server verification.",style=MaterialTheme.typography.bodySmall)
                    checkout?.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                    if(checkout?.busy==true) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(checkout!=null) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(!signedIn) TextButton(onClick=onLogin) {Text("Log in to continue")}
                        else {
                            TextButton(onClick=onRestore,enabled=!checkout.busy) {Text("Restore purchases")}
                            TextButton(onClick=onPayments) {Text("Payments")}
                        }
                    }
                }
            }
            planPreviews.forEach { plan ->
                Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
                    border=BorderStroke(1.dp,if(plan.id=="MONTHLY") MaterialTheme.colorScheme.primary.copy(alpha=.65f)
                        else MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().testTag("plan_${plan.id}")) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Text(plan.title,style=MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(checkout?.prices?.get(plan.id) ?: plan.price,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                            Column(Modifier.weight(1f),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(plan.validity,style=MaterialTheme.typography.labelMedium)
                                Text(plan.payment,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            PlanBenefit("Ad-free");PlanBenefit("Higher quality");PlanBenefit(plan.downloads)
                        }
                        OutlinedButton(onClick={onPurchase(plan.id)},enabled=checkout?.enabled==true && checkout.prices.containsKey(plan.id) && !membership.loading && !membership.adFree,shape=RoundedCornerShape(12.dp),
                            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("purchase_${plan.id}")) {
                            Text(if(checkout==null) "Coming soon" else if(membership.adFree) "Plan already active" else if(checkout.prices.containsKey(plan.id)) "Continue with Google Play" else "Unavailable")
                        }
                    }
                }
            }
            Text("Quality depends on the video. Day and Week passes don’t renew automatically. Monthly renews at the displayed monthly price until cancelled in Google Play. No plan changes are offered while a plan is active.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PlanBenefit(text: String) {
    Surface(color=MaterialTheme.colorScheme.surfaceContainerHighest,shape=RoundedCornerShape(8.dp)) {
        Text(text,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(horizontal=9.dp,vertical=6.dp))
    }
}

@Preview(widthDp=360,heightDp=800)
@Composable
private fun PlansPreview() { VidooraTheme { MembershipPlansScreen(MembershipView(loading=false),{},{}) } }

@Preview(widthDp=640,heightDp=800,fontScale=1.4f)
@Composable
private fun LargePlansPreview() { VidooraTheme { MembershipPlansScreen(MembershipView(loading=false),{},{}) } }
