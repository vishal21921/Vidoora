package com.example.vidoora.feature.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.core.network.SharedVideoLink
import com.example.vidoora.feature.ads.ViewerAdsController
import com.example.vidoora.feature.ads.ViewerBanner
import com.example.vidoora.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoDetailsScreen(state: VideoDetailsState,onRefresh: ()->Unit,onEnableSharing: ()->Unit,onStopSharing: ()->Unit,
    onShare: ()->Unit,onWatch: ()->Unit,onBack: ()->Unit,onBlock: ()->Unit,onEdit: ()->Unit,onCancelEdit: ()->Unit,onSaveEdit: ()->Unit,
    onEditTitle: (String)->Unit,onEditDescription: (String)->Unit,onDelete: ()->Unit,onMove: ()->Unit,onMoveSelect: (String?)->Unit,onMoveSave: ()->Unit,onMoveCancel: ()->Unit,
    signedIn: Boolean=true,thumbnailLoader: PrivateThumbnailLoader?=null,viewerEntry: Boolean=false,onChangeThumbnail: ((android.net.Uri)->Unit)?=null,onDownload: (() -> Unit)?=null,
    viewerAds: ViewerAdsController? = null) {
    val thumbnailPicker=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) onChangeThumbnail?.invoke(uri)
    }
    if(state.moving) MoveVideoDialog(state,onMoveSelect,onMoveSave,onMoveCancel,onMove)
    if(state.editing) EditVideoDialog(state,onCancelEdit,onSaveEdit,onEditTitle,onEditDescription)
    var deleting by remember(state.video?.id) { mutableStateOf(false) }
    if(deleting) DeleteVideoConfirmation(onKeep={ deleting=false },onDelete={ deleting=false; if(!state.loading && state.video?.isOwner==true) onDelete() })
    var blocking by remember(state.video?.id) { mutableStateOf(false) }
    if(blocking) AlertDialog(onDismissRequest={ blocking=false },title={ Text("Block uploader?") },
        text={ Text("Hide this uploader's videos. Unblock anytime from Profile.") },
        confirmButton={ TextButton(onClick={ blocking=false; onBlock() }) { Text("Block") } },
        dismissButton={ TextButton(onClick={ blocking=false }) { Text("Cancel") } })
    if (!state.loading && state.video == null && state.error != null && !state.deleted && !hasInternetConnection()) {
        NoInternetScreen(onRefresh, onBack)
        return
    }
    if(state.deleted || (!state.loading && state.video==null && state.error?.startsWith("Video unavailable")==true)) {
        VideoUnavailableScreen(onBack,deleted=state.deleted)
        return
    }
    val notices = remember { SnackbarHostState() }
    var lastNotice by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.notice) {
        val notice = state.notice
        if (notice == null) lastNotice = null
        else if (notice != lastNotice) {
            lastNotice = notice
            notices.showSnackbar(when {
                notice.startsWith("Uploader blocked") -> "Uploader blocked"
                notice.startsWith("Video details updated") -> "Changes saved"
                notice.startsWith("Video folder updated") -> "Folder updated"
                else -> notice
            })
        }
    }
    Scaffold(snackbarHost={ SnackbarHost(notices) },topBar={ VideoPageHeader("Video details",onBack,onRefresh,!state.loading) }) { padding ->
        LazyColumn(Modifier.fillMaxHeight().padding(padding).readableWidth(640.dp),
            contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val showBanners = viewerEntry && state.video?.canPlay == true && state.error == null
            if (showBanners) item { ViewerBanner(viewerAds, true, horizontalPadding = 0.dp) }
            if(state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { FormError(it) } }
            state.statusError?.let { item { Text("Status update failed. Retrying…",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) } }
            state.video?.let { video ->
                val shared=video.published && video.visibility=="UNLISTED"
                val pending=VideoProcessingStatus.pending(video.publicationState)
                item { DetailThumbnail(video,thumbnailLoader) }
                item { Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(video.title,style=MaterialTheme.typography.titleLarge)
                    Text(video.creatorName,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    VideoBadge(when(video.publicationState) {
                        "PROCESSING" -> "Processing"
                        "UPLOADED" -> "Queued"
                        else -> VideoProcessingStatus.label(video.publicationState,video.isOwner)
                    },ready=video.publicationState=="PROCESSED",pending=pending)
                    if(shared || video.isOwner) VideoBadge(if(shared) "Shared by link" else "Private",icon=if(shared) R.drawable.ic_ui_link else R.drawable.ic_ui_lock)
                } }
                if(pending) item { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    video.processingPercent?.takeIf {video.publicationState=="PROCESSING"}?.let {percent ->
                        Text("Processing ${percent.coerceIn(0,99)}%",style=MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress={percent.coerceIn(0,99)/100f},modifier=Modifier.fillMaxWidth())
                    }
                    Text(when(video.publicationState) {
                        "UPLOADING" -> "Uploading your video."
                        "UPLOADED" -> "Upload complete. Waiting for processing."
                        else -> "Preparing your video for playback."
                    },style=MaterialTheme.typography.bodyMedium)
                    Text("Updates automatically.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(video.publicationState!="UPLOADING" && (video.publicationState!="PROCESSING" || video.processingPercent==null)) LinearProgressIndicator(Modifier.fillMaxWidth())
                } }
                if(video.publicationState=="PROCESSING_FAILED") item { Text("Processing failed. Check the processor, or upload again.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error) }
                video.description?.takeIf { it.isNotBlank() }?.let { description -> item { Text(description,style=MaterialTheme.typography.bodyMedium) } }
                if(VideoViewingPolicy.canWatch(viewerEntry,video.canPlay)) item { PrimaryAction("Watch video",onWatch,enabled=!state.loading) }
                if(video.canPlay && onDownload!=null) item { OutlinedButton(onClick=onDownload,shape=MaterialTheme.shapes.small) { Text("Download") } }
                if(video.isOwner) {
                    if(video.publicationState=="PROCESSED" && onChangeThumbnail!=null) item {
                        OutlinedButton(onClick={ thumbnailPicker.launch("image/*") },enabled=!state.loading,shape=MaterialTheme.shapes.small) { Text("Change thumbnail") }
                    }
                    item { OwnerSharingCard(video,!state.loading,onEnableSharing,onShare,onStopSharing) }
                    item { Surface(shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()) {
                        Column {
                            DetailAction("Edit details",R.drawable.ic_ui_edit,!state.loading,onEdit)
                            HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant)
                            DetailAction("Move to folder",R.drawable.ic_ui_folder,!state.loading,onMove)
                        }
                    } }
                    item { OutlinedButton(onClick={ deleting=true },enabled=!state.loading,shape=RoundedCornerShape(10.dp),
                        border=BorderStroke(1.dp,MaterialTheme.colorScheme.error.copy(alpha=.5f)),
                        colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Delete video") } }
                } else if(signedIn) item {
                    TextButton(onClick={ blocking=true },enabled=!state.loading) { UiIcon(R.drawable.ic_ui_blocked); Spacer(Modifier.width(8.dp)); Text("Block uploader") }
                }
            }
            if(state.video==null && !state.loading) item { TextButton(onClick=onBack) { Text("Go back") } }
            if (showBanners) item { ViewerBanner(viewerAds, true, horizontalPadding = 0.dp) }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenVideoLinkScreen(onOpen: (SharedVideoLink)->Unit,onBack: ()->Unit) {
    Scaffold(topBar={ TopAppBar(title={ Text("Open video link") },navigationIcon={ IconButton(onClick=onBack) { com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_back,"Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Paste the complete link. It opens automatically.")
            com.example.vidoora.feature.home.VideoLinkField(onOpen)
        }
    }
}




