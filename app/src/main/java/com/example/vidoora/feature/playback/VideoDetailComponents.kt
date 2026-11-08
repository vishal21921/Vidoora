package com.example.vidoora.feature.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPageHeader(title: String,onBack: ()->Unit,onRefresh: (() -> Unit)?=null,enabled: Boolean=true) {
    CenterAlignedTopAppBar(title={ Text(title,style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        navigationIcon={ IconButton(onClick=onBack) { UiIcon(R.drawable.ic_ui_back,"Back") } },
        actions={ if(onRefresh!=null) IconButton(onClick=onRefresh,enabled=enabled) { UiIcon(R.drawable.ic_ui_refresh,"Refresh") } })
}

@Composable
fun VideoUnavailableScreen(onBack: ()->Unit,deleted: Boolean=false) {
    Scaffold(topBar={ VideoPageHeader("",onBack) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val available=maxHeight
            Column(Modifier.readableWidth(480.dp).verticalScroll(rememberScrollState()).heightIn(min=available).padding(28.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer,
                    modifier=Modifier.size(64.dp)) {
                    Box(contentAlignment=Alignment.Center) { UiIcon(R.drawable.ic_ui_unavailable,modifier=Modifier.size(32.dp)) }
                }
                Spacer(Modifier.height(18.dp))
                Text(if(deleted) "Video deleted" else "Video unavailable",style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(if(deleted) "This video and its shared links are no longer available."
                    else "The link may have expired, sharing stopped, or the video was removed.",
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                Button(onClick=onBack,shape=RoundedCornerShape(10.dp),modifier=Modifier.heightIn(min=48.dp)) { Text("Go back") }
            }
        }
    }
}

@Composable
fun DetailThumbnail(video: VideoDetailsDto,loader: PrivateThumbnailLoader?) {
    val preview by produceState<android.graphics.Bitmap?>(null,video.id,video.isOwner,video.publicationState,video.thumbnailVersion,loader) {
        if(video.publicationState=="PROCESSED" && loader!=null) value=loader.load(video.id)
    }
    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainer,
        modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
        Box(contentAlignment=Alignment.Center) {
            val bitmap=preview
            if(bitmap!=null) Image(bitmap.asImageBitmap(),contentDescription="Video preview",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
            else if(video.publicationState!="PROCESSING") UiIcon(R.drawable.ic_ui_video,modifier=Modifier.size(42.dp))
            if(video.publicationState=="PROCESSING") CircularProgressIndicator(Modifier.size(32.dp),strokeWidth=3.dp)
        }
    }
}

@Composable
fun VideoBadge(text: String,ready: Boolean=false,pending: Boolean=false,icon: Int?=null) {
    val dark=MaterialTheme.colorScheme.surface.luminance()<0.5f
    val container=when {
        ready -> if(dark) androidx.compose.ui.graphics.Color(0xFF163D36) else androidx.compose.ui.graphics.Color(0xFFDFF5EE)
        pending -> if(dark) androidx.compose.ui.graphics.Color(0xFF493B16) else androidx.compose.ui.graphics.Color(0xFFFFF0CB)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val foreground=when {
        ready -> if(dark) androidx.compose.ui.graphics.Color(0xFFA8E8D6) else androidx.compose.ui.graphics.Color(0xFF20675A)
        pending -> if(dark) androidx.compose.ui.graphics.Color(0xFFFFDA87) else androidx.compose.ui.graphics.Color(0xFF715313)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape=RoundedCornerShape(5.dp),color=container,contentColor=foreground) {
        Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            if(icon!=null) UiIcon(icon,modifier=Modifier.size(14.dp))
            Text(text,style=MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun OwnerSharingCard(video: VideoDetailsDto,enabled: Boolean,onEnable: ()->Unit,onShare: ()->Unit,onStop: ()->Unit) {
    val shared=video.published && video.visibility=="UNLISTED" && video.canPlay
    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                UiIcon(if(shared) R.drawable.ic_ui_link else R.drawable.ic_ui_lock,modifier=Modifier.size(20.dp))
                Text(if(shared) "Shared by link" else if(video.canPlay) "Private video" else "Private by default",style=MaterialTheme.typography.titleMedium)
            }
            Text(if(shared) "Anyone with the complete link can watch."
                else if(video.canPlay) "Share your video with a private link." else if(VideoProcessingStatus.pending(video.publicationState)) "Enable link sharing after processing." else "This video is not ready to share.",style=MaterialTheme.typography.bodyMedium)
            if(shared) {
                Text("Links expire after 30 days.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryAction("Share video",onShare,enabled=enabled)
                TextButton(onClick=onStop,enabled=enabled,modifier=Modifier.align(Alignment.CenterHorizontally),
                    colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Stop sharing") }
            } else if(video.canPlay) TextButton(onClick=onEnable,enabled=enabled,contentPadding=PaddingValues(horizontal=0.dp,vertical=8.dp)) { Text("Enable link sharing") }
        }
    }
}

@Composable
fun DetailAction(text: String,icon: Int,enabled: Boolean,onClick: ()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,role=androidx.compose.ui.semantics.Role.Button,onClick=onClick)
        .heightIn(min=52.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        UiIcon(icon)
        Text(text,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
        UiIcon(R.drawable.ic_ui_chevron,modifier=Modifier.size(18.dp))
    }
}

@Composable
fun DeleteVideoConfirmation(onKeep: ()->Unit,onDelete: ()->Unit) {
    AlertDialog(onDismissRequest=onKeep,title={ Text("Delete video?",modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,style=MaterialTheme.typography.titleLarge) },
        text={ Text("This permanently removes your video and stops its shared links. This cannot be undone.",textAlign=TextAlign.Center,style=MaterialTheme.typography.bodyMedium) },
        confirmButton={ Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick=onKeep,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Keep video") }
            Button(onClick=onDelete,shape=RoundedCornerShape(10.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Delete permanently") }
        } },shape=RoundedCornerShape(18.dp))
}





