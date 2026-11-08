package com.example.vidoora.feature.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.feature.ads.*
import com.example.vidoora.feature.membership.*
import com.example.vidoora.feature.playback.HistoryVideoThumbnail
import com.example.vidoora.feature.playback.PrivateThumbnailLoader
import com.example.vidoora.feature.playback.WatchedVideo
import com.example.vidoora.ui.theme.*

const val VIDOORA_SUPPORT_EMAIL="vidoora.support@gmail.com"
enum class SupportTopic(val title: String) { PAYMENTS("Payments"), ACCOUNT("Account"), PLAYBACK("Playback"), OTHER("Other") }

/** Opens a user-reviewed draft only. No email is sent by the application. */
fun supportEmailIntent(userId: String?,topic: SupportTopic,transactionId: String,message: String): Intent {
    val reference=transactionId.replace("\r","").replace("\n","").trim().take(128)
    val body=buildString {
        append("User ID: ").append(userId ?: "Guest viewer").append('\n')
        if(reference.isNotEmpty()) append("Transaction ID: ").append(reference).append('\n')
        append("App version: ").append(com.example.vidoora.BuildConfig.VERSION_NAME).append('\n')
        append("Topic: ").append(topic.title).append("\n\n").append(message.take(2000))
    }
    // Keep user text out of the mailto URI: some clients decode query separators before parsing.
    return Intent(Intent.ACTION_SENDTO,Uri.fromParts("mailto",VIDOORA_SUPPORT_EMAIL,null))
        .putExtra(Intent.EXTRA_SUBJECT,"Vidoora support - ${topic.title}")
        .putExtra(Intent.EXTRA_TEXT,body)
}

@Composable
private fun AccountNotice(title: String,message: String,icon: Int,onAction: ()->Unit,action: String) {
    ReferenceCard {
        Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            UiIcon(icon,modifier=Modifier.size(36.dp))
            Text(title,style=MaterialTheme.typography.titleLarge)
            Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryAction(action,onAction)
        }
    }
}

@Composable
fun HistoryScreen(
    videos: List<WatchedVideo>,
    thumbnailLoader: PrivateThumbnailLoader,
    loading: Boolean,
    failed: Boolean,
    onOpen: (WatchedVideo) -> Unit,
    onRemove: (String) -> Unit,
    identityKey: String = "guest",
    onHome: () -> Unit
) {
    val listState = rememberRestoringListState(!loading && !failed, identityKey)
    Scaffold(topBar={ ReferenceHeader("History") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(600.dp), contentPadding = PaddingValues(20.dp),
            state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                loading -> item { CircularProgressIndicator() }
                failed -> item { Text("Watch history is unavailable.") }
                videos.isEmpty() -> item { AccountNotice("No watched videos yet","Videos appear here after you start watching.",
                    R.drawable.ic_ui_history,onHome,"Open a video link") }
                else -> {
                    item { Text("Recently watched on this device", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(videos, key = { it.id }) { video ->
                        ReferenceCard {
                            Row(Modifier.fillMaxWidth().clickable { onOpen(video) }.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                HistoryVideoThumbnail(video, thumbnailLoader, Modifier.width(104.dp).aspectRatio(16f / 9f))
                                Column(Modifier.weight(0.58f)) {
                                    Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(remember(video.watchedAt) {
                                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                                            .format(java.util.Date(video.watchedAt))
                                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onRemove(video.id) }) { UiIcon(R.drawable.ic_ui_unavailable,"Remove from history") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PaymentHistoryScreen(signedIn: Boolean,onBack: ()->Unit,onLogin: ()->Unit,onPlans: ()->Unit,onSupport: ()->Unit,
    records: (@Composable ()->Unit)?=null) {
    Scaffold(topBar={ ReferenceHeader("Payments",onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth(600.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(!signedIn) AccountNotice("Sign in to view payments","Your purchases and receipts will belong to your account.",R.drawable.ic_ui_person,onLogin,"Log in")
            else if(records!=null) records()
            else AccountNotice("Payment history is coming soon","Payments aren’t enabled yet. Transaction IDs and payment status will appear here when billing is available.",
                R.drawable.ic_ui_history,onPlans,"View plans")
            AccountSection("HELP") { AccountActionRow("Payment help","Contact Vidoora support",R.drawable.ic_ui_email,onClick=onSupport) }
        }
    }
}

@Composable
fun SupportCentreScreen(userId: String?,onBack: ()->Unit,initialReference: String="",onEmail: (SupportTopic,String,String)->Unit) {
    var topic by remember(userId,initialReference) { mutableStateOf(if(initialReference.isBlank()) SupportTopic.OTHER else SupportTopic.PAYMENTS) }
    var menu by remember { mutableStateOf(false) }
    var reference by remember(userId,initialReference) { mutableStateOf(initialReference.take(128)) }
    var message by remember(userId) { mutableStateOf("") }
    val clipboard=LocalClipboardManager.current
    Scaffold(topBar={ ReferenceHeader("Support centre",onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth(600.dp).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ReferenceCard {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(VIDOORA_SUPPORT_EMAIL,style=MaterialTheme.typography.titleSmall)
                    Text("User ID: ${userId ?: "Guest viewer"}",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ clipboard.setText(AnnotatedString(VIDOORA_SUPPORT_EMAIL)) }) { Text("Copy support email") }
                }
            }
            Box {
                OutlinedButton(onClick={ menu=true },shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) { Text("Topic: ${topic.title}") }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    SupportTopic.entries.forEach { item -> DropdownMenuItem(text={Text(item.title)},onClick={topic=item;menu=false}) }
                }
            }
            VidooraTextField(reference,{reference=it.replace("\r","").replace("\n","").take(128)},"Transaction ID (optional)",singleLine=true)
            VidooraTextField(message,{message=it.take(2000)},"How can we help?",singleLine=false,modifier=Modifier.heightIn(min=150.dp))
            Text("Your email draft includes your user ID. Add a screenshot in your email app if needed.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryAction("Open email app",{onEmail(topic,reference,message)},enabled=message.isNotBlank(),icon=R.drawable.ic_ui_email)
        }
    }
}

@Composable
fun AccountSettingsScreen(membership: MembershipView,ads: ViewerAdsController,onBack: ()->Unit,onPlans: ()->Unit,
    onRemoveUploader: (() -> Unit)? = null, onUploadHealth: (() -> Unit)? = null,
    onAdminPayments: (() -> Unit)? = null, onAdAnalytics: (() -> Unit)? = null,
    onCreatorCode: (() -> Unit)? = null, onServiceHealth: (() -> Unit)? = null) {
    val privacy by ads.state.collectAsState()
    val activity=androidx.compose.ui.platform.LocalContext.current.adsActivity()
    Scaffold(topBar={ ReferenceHeader("Settings",onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth(600.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(20.dp)) {
            MembershipEntryCard(membership,onPlans)
            if (onRemoveUploader != null || onUploadHealth != null) AccountSection("ADMIN") {
                if (onAdminPayments != null) AccountActionRow("Subscriptions & Payments","User plans and transaction status",R.drawable.ic_ui_history) { onAdminPayments() }
                if (onAdAnalytics != null) AccountActionRow("Ads Analytics","Interstitial and banner activity",R.drawable.ic_ui_history) { onAdAnalytics() }
                if (onCreatorCode != null) AccountActionRow("Creator invite code","Five-minute upload invitation",R.drawable.ic_ui_queue) { onCreatorCode() }
                if (onUploadHealth != null) AccountActionRow("Upload Health","Queued, processing and failed videos",R.drawable.ic_ui_queue) { onUploadHealth() }
                if (onServiceHealth != null) AccountActionRow("Service Health","Backend and processor",R.drawable.ic_ui_queue) { onServiceHealth() }
                if (onRemoveUploader != null) AccountActionRow("Remove uploader","Manage creator access",R.drawable.ic_ui_lock) { onRemoveUploader() }
            }
            AccountSection("PRIVACY") {
                AccountActionRow("Ad privacy choices",if(privacy.privacyRequired) "Manage your consent" else "Available when required by ad setup",
                    R.drawable.ic_ui_lock,privacy.privacyRequired && !privacy.privacyBusy && activity!=null) { activity?.let(ads::privacyOptions) }
            }
            privacy.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            Text("Vidoora ${com.example.vidoora.BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
