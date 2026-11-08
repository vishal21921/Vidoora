package com.example.vidoora.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.core.network.SharedVideoLink
import com.example.vidoora.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(signedIn: Boolean=false,onPlans: (() -> Unit)?=null,
    membership: com.example.vidoora.feature.membership.MembershipView=com.example.vidoora.feature.membership.MembershipView(),onOpenLink: (SharedVideoLink)->Unit) {
    Scaffold(topBar={
        TopAppBar(title={ VidooraBrand() },actions={
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
                modifier=Modifier.padding(end=20.dp)) {
                Text(if(signedIn) "Creator" else "Viewer",style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=12.dp,vertical=6.dp))
            }
        })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val availableHeight=maxHeight
            Column(Modifier.readableWidth(600.dp).verticalScroll(rememberScrollState())
                .heightIn(min=availableHeight).padding(horizontal=24.dp,vertical=24.dp),
                verticalArrangement=Arrangement.SpaceBetween,horizontalAlignment=Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Open a shared video",style=MaterialTheme.typography.headlineSmall)
                    Text("Paste a Vidoora link to start watching.",style=MaterialTheme.typography.bodyLarge,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    VideoLinkField(onOpenLink)
                    Text("Video details open automatically.",style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.align(Alignment.CenterHorizontally))
                }
                if(onPlans!=null) com.example.vidoora.feature.membership.MembershipEntryCard(membership,onPlans,
                    modifier=Modifier.padding(top=24.dp))
                Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier=Modifier.padding(top=24.dp)) {
                    Row(Modifier.padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        UiIcon(R.drawable.ic_ui_lock,modifier=Modifier.size(16.dp))
                        Text("Uploading requires an account.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Capability input and clipboard reads stay in memory; clipboard is read only after a tap. */
@Suppress("DEPRECATION")
@Composable
fun VideoLinkField(onOpen: (SharedVideoLink)->Unit) {
    val clipboard=LocalClipboardManager.current
    val scope=rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    val input=remember(scope) { VideoLinkEntry(scope) { open(it) } }
    val state by input.state.collectAsState()
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=state.text,onValueChange=input::change,
            placeholder={ Text("Paste video link") },singleLine=true,isError=state.invalid,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),shape=RoundedCornerShape(12.dp),
            leadingIcon={ UiIcon(R.drawable.ic_ui_link) },
            trailingIcon={ TextButton(onClick={ clipboard.getText()?.text?.let(input::change) }) { Text("Paste") } },
            modifier=Modifier.fillMaxWidth(),
            colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant))
        if(state.invalid) Text("Paste the complete Vidoora link, including everything after #.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    }
}

