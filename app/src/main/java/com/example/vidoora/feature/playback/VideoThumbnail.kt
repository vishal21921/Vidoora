package com.example.vidoora.feature.playback

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun VideoThumbnail(video: LibraryVideoDto,loader: PrivateThumbnailLoader,modifier: Modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
    ThumbnailPreview(video.title, video.durationSeconds, modifier,
        requestKey = listOf(video.id, video.thumbnailAvailable, video.thumbnailVersion, loader)) {
        if (video.thumbnailAvailable) loader.load(video.id)?.asImageBitmap() else null
    }
}

@Composable
fun HistoryVideoThumbnail(video: WatchedVideo, loader: PrivateThumbnailLoader, modifier: Modifier) {
    ThumbnailPreview(video.title, 0, modifier, requestKey = listOf(video.id, video.capability, loader)) {
        loader.load(video.id, video.capability)?.asImageBitmap()
    }
}

@Composable
private fun ThumbnailPreview(title: String, durationSeconds: Int, modifier: Modifier,
    requestKey: List<Any?>, load: suspend () -> ImageBitmap?) {
    var loading by remember(requestKey) { mutableStateOf(true) }
    val preview by key(requestKey) { produceState<ImageBitmap?>(null) {
        try { value = load() } finally { loading = false }
    } }
    Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceVariant,modifier=modifier) {
        Box(contentAlignment=Alignment.Center) {
            val image=preview
            if(image!=null) Image(image,contentDescription="Preview of $title",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_video,
                if (loading) "Loading preview" else "Preview unavailable")
            if (loading) LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp))
            if(durationSeconds>0) Surface(color=androidx.compose.ui.graphics.Color.Black.copy(alpha=.75f),shape=MaterialTheme.shapes.extraSmall,
                modifier=Modifier.align(Alignment.BottomEnd).padding(5.dp)) {
                Text("%d:%02d".format(durationSeconds/60,durationSeconds%60),color=androidx.compose.ui.graphics.Color.White,
                    style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=5.dp,vertical=2.dp))
            }
        }
    }
}
