package com.example.vidoora.feature.upload

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.*

@Composable
fun CreatorHubScreen(signedIn: Boolean,onLogin: ()->Unit,onNewUpload: ()->Unit,onVideos: ()->Unit,
    onQueue: ()->Unit,onFolders: ()->Unit,onBlocked: ()->Unit) {
    Scaffold(topBar={ ReferenceHeader("Upload") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth(600.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text("Your creator space",style=MaterialTheme.typography.headlineSmall)
            if(signedIn) PrimaryAction("Upload a video",onNewUpload,icon=R.drawable.ic_ui_upload_line)
            else ReferenceCard {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Sign in to upload",style=MaterialTheme.typography.titleLarge)
                    Text("Upload and manage your own videos. Watching shared links stays free to access without login.",style=MaterialTheme.typography.bodyMedium)
                    PrimaryAction("Log in / Create account",onLogin,icon=R.drawable.ic_ui_login)
                }
            }
            AccountSection("YOUR VIDEOS") {
                AccountActionRow("My Videos","Manage videos and sharing",R.drawable.ic_ui_library,signedIn,onVideos)
                AccountRowDivider()
                AccountActionRow("Upload queue","Progress, pause and resume",R.drawable.ic_ui_queue,signedIn,onQueue)
                AccountRowDivider()
                AccountActionRow("Folders","Organise your videos",R.drawable.ic_ui_folder,signedIn,onFolders)
            }
            AccountSection("VIEWING CONTROLS") {
                AccountActionRow("Blocked uploaders","Manage blocked accounts",R.drawable.ic_ui_blocked,signedIn,onBlocked)
            }
        }
    }
}
