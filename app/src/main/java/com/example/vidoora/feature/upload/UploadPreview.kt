package com.example.vidoora.feature.upload

import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.vidoora.R
import com.example.vidoora.core.database.entity.UploadTaskEntity
import com.example.vidoora.feature.playback.PrivateThumbnailLoader
import com.example.vidoora.ui.theme.UiIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun UploadPreview(task: UploadTaskEntity,loader: PrivateThumbnailLoader?=null,modifier: Modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null,task.id,task.status,loader) {
        val remote=if(task.status=="UPLOADED") loader?.load(task.videoId) else null
        value=remote ?: withContext(Dispatchers.IO) {
            val previews=File(context.cacheDir,"upload_previews")
            val cached=File(previews,"${task.id}.jpg")
            if(cached.parentFile?.canonicalFile!=previews.canonicalFile) return@withContext null
            if(cached.isFile && cached.length() in 1..262144) {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(cached.absolutePath,bounds)
                if(bounds.outWidth in 1..320 && bounds.outHeight in 1..180) return@withContext BitmapFactory.decodeFile(cached.absolutePath)
            }
            val file=File(task.sourceUri)
            val parents=listOf(File(context.filesDir,"upload_drafts").canonicalFile,File(context.cacheDir,"upload_drafts").canonicalFile)
            if(file.parentFile?.canonicalFile?.let { it in parents }!=true || !file.isFile || android.os.Build.VERSION.SDK_INT<27) null else try {
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180)?.also { frame ->
                        if(previews.isDirectory || previews.mkdirs()) cached.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,it) }
                    }
                } finally { retriever.release() }
            } catch(_: Exception) { null }
        }
    }
    Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=modifier) {
        Box(contentAlignment=Alignment.Center) {
            val image=bitmap
            if(image!=null) Image(image.asImageBitmap(),"Video preview",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else UiIcon(R.drawable.ic_ui_video,"Video preview unavailable")
        }
    }
}
