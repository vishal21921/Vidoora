package com.example.vidoora.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.core.network.dto.UserDto
import com.example.vidoora.feature.ads.ViewerAdsController
import com.example.vidoora.feature.membership.MembershipView
import com.example.vidoora.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Suppress("UNUSED_PARAMETER","DEPRECATION")
@Composable
fun ProfileScreen(user: UserDto?,onBackClick: ()->Unit,onLogoutClick: ()->Unit,
    onLoginClick: ()->Unit={},onVideosClick: ()->Unit={},onQueueClick: ()->Unit={},onBlockedClick: ()->Unit={},
    loggingOut: Boolean=false,logoutError: String?=null,onCreateAccount: ()->Unit=onLoginClick,
    ads: ViewerAdsController?=null,onPlans: (() -> Unit)?=null,membership: MembershipView=MembershipView(),
    onPayments: ()->Unit={},onDownloads: ()->Unit={},onSupport: ()->Unit={},onSettings: ()->Unit={},creatorInviteContent: (@Composable ()->Unit)?=null,
    onAnalytics: ()->Unit={}) {
    val clipboard=LocalClipboardManager.current
    var copied by remember(user?.id) { mutableStateOf(false) }
    Scaffold(topBar={ ReferenceHeader("Profile",actions={
        if(user==null) Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
            modifier=Modifier.padding(end=16.dp)) {
            Text("Guest",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=12.dp,vertical=6.dp))
        }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth(600.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(20.dp)) {
            logoutError?.let { FormError(it) }
            if(loggingOut) LinearProgressIndicator(Modifier.fillMaxWidth())
            ReferenceCard {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    if(user==null) {
                        Text("Watch without an account",style=MaterialTheme.typography.titleLarge)
                        Text("Sign in to manage your uploads and account.",style=MaterialTheme.typography.bodyMedium,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        PrimaryAction("Log in",onLoginClick,enabled=!loggingOut,icon=R.drawable.ic_ui_login)
                        VidooraOutlinedButton("Create account",onCreateAccount,enabled=!loggingOut)
                    } else {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.size(52.dp)) {
                                Box(contentAlignment=Alignment.Center) {
                                    val initials=user.displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
                                    Text(initials.ifEmpty {"V"},style=MaterialTheme.typography.titleLarge)
                                }
                            }
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(user.displayName,style=MaterialTheme.typography.titleMedium)
                                Text(if(user.canUpload) "Creator" else "Viewer",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                                Text(user.email,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("User ID",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(user.publicId.ifBlank {user.id},style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)
                            }
                            TextButton(onClick={clipboard.setText(AnnotatedString(user.publicId.ifBlank {user.id}));copied=true},enabled=!loggingOut) {
                                UiIcon(R.drawable.ic_ui_copy,modifier=Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text(if(copied) "Copied" else "Copy")
                            }
                        }
                    }
                }
            }
            if(user?.canUpload == true) AccountSection("CREATOR") {
                AccountActionRow("Video analytics","Daily views and top videos",R.drawable.ic_ui_history,!loggingOut,onAnalytics)
            }
            if(user!=null && !user.canUpload) creatorInviteContent?.invoke()
            if(onPlans!=null) {
                Surface(onClick=onPlans,enabled=!loggingOut,shape=RoundedCornerShape(12.dp),
                    color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.4f)),
                    modifier=Modifier.fillMaxWidth().testTag("membership_entry")) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("CURRENT PLAN",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(membership.loading) "Checking access…" else if(membership.adFree) when(membership.planId) {
                            "DAY"->"Day Pass";"WEEK"->"Week Pass";"MONTHLY"->"Monthly Ad-Free";else->"Free"
                        } else "Free",style=MaterialTheme.typography.titleLarge)
                        if(!membership.loading) Text(if(membership.adFree) "Ad-free · Available higher qualities" else "With ads · Up to 480p",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(membership.adFree && !membership.loading) membership.expiresAt?.let { value ->
                            val date=runCatching { DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrNull()
                            if(date!=null) Text("Valid until $date",style=MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Subscription plans",style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
                            UiIcon(R.drawable.ic_ui_chevron,modifier=Modifier.size(18.dp))
                        }
                    }
                }
            }
            AccountSection("MEMBERSHIP") {
                AccountActionRow("Payments & transactions","Receipts and payment status",R.drawable.ic_ui_history,!loggingOut,onPayments)
                AccountRowDivider()
                AccountActionRow("Downloads","Private offline videos",R.drawable.ic_ui_video,!loggingOut,onDownloads)
            }
            if(user!=null) AccountSection("VIEWING CONTROLS") {
                AccountActionRow("Blocked uploaders","Manage blocked accounts",R.drawable.ic_ui_blocked,!loggingOut,onBlockedClick)
            }
            AccountSection("HELP & APP") {
                AccountActionRow("Support centre",VIDOORA_SUPPORT_EMAIL,R.drawable.ic_ui_email,!loggingOut,onSupport)
                AccountRowDivider()
                AccountActionRow("Settings","Plans and ad privacy choices",R.drawable.ic_ui_more,!loggingOut,onSettings)
                AccountRowDivider()
                AccountActionRow("Share app","Available after the public release",R.drawable.ic_ui_link,false,{})
                AccountRowDivider()
                AccountActionRow("Rate Vidoora","Available after the Play release",R.drawable.ic_ui_play,false,{})
            }
            if(user!=null) OutlinedButton(onClick=onLogoutClick,enabled=!loggingOut,shape=RoundedCornerShape(12.dp),
                colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),
                border=BorderStroke(1.dp,MaterialTheme.colorScheme.error.copy(alpha=.4f)),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)) {
                UiIcon(R.drawable.ic_ui_logout);Spacer(Modifier.width(8.dp));Text(if(loggingOut) "Logging out…" else "Log out")
            }
        }
    }
}
