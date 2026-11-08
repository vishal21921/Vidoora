package com.example.vidoora.feature.upload

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.example.vidoora.R
import com.example.vidoora.core.database.entity.UploadTaskEntity
import com.example.vidoora.ui.theme.*

@Composable
fun UploadQueueScreen(tasks: List<UploadTaskEntity>,onPauseClick: (String)->Unit,onResumeClick: (String)->Unit,
    onRetryClick: (String)->Unit,onCancelClick: (String,String,String)->Unit,onNewUploadClick: ()->Unit,
    onBackClick: ()->Unit,actionError: String?=null,onOpenVideo: ((String)->Unit)?=null,onDeleteClick: ((String)->Unit)?=null,
    thumbnailLoader: com.example.vidoora.feature.playback.PrivateThumbnailLoader?=null,processing: Map<String,QueueProcessing> = emptyMap()) {
    var target by remember { mutableStateOf<UploadTaskEntity?>(null) }
    target?.let { task -> AlertDialog(onDismissRequest={ target=null },title={ Text("Delete video?") },
        text={ Text("Remove this upload and video. Shared links will stop working.") },
        confirmButton={ TextButton(onClick={ target=null; onDeleteClick?.invoke(task.id) }) { Text("Delete",color=MaterialTheme.colorScheme.error) } },
        dismissButton={ TextButton(onClick={ target=null }) { Text("Keep video") } }) }
    Scaffold(topBar={ ReferenceHeader("Upload queue",onBackClick,actions={ TextButton(onClick=onNewUploadClick) { Text("New upload") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth().padding(horizontal=16.dp),
            contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            actionError?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            if(tasks.isEmpty()) item { VidooraEmptyState("No uploads","Your uploads appear here.",R.drawable.ic_ui_upload_line) }
            items(tasks,key={ it.id }) { task ->
                var revealed by remember(task.id) { mutableStateOf(false) }
                var drag by remember(task.id) { mutableFloatStateOf(0f) }
                val offset by animateDpAsState(if(revealed) (-96).dp else 0.dp,label="Upload delete reveal")
                Box(Modifier.clipToBounds().pointerInput(task.id) {
                    detectHorizontalDragGestures(onDragStart={ drag=0f },onHorizontalDrag={ change,amount -> change.consume(); drag+=amount },
                        onDragEnd={ if(drag < -48.dp.toPx()) revealed=true else if(drag>48.dp.toPx()) revealed=false })
                }) {
                    if(revealed && onDeleteClick!=null) TextButton(onClick={ target=task },enabled=task.status!="DELETING",
                        modifier=Modifier.align(Alignment.CenterEnd).width(96.dp).heightIn(min=64.dp)) {
                        Text("Delete",color=MaterialTheme.colorScheme.error)
                    }
                    Box(Modifier.offset(x=offset)) {
                    UploadTaskCard(task,{ onPauseClick(task.id) },{ onResumeClick(task.id) },{ onRetryClick(task.id) },
                        { onCancelClick(task.id,task.videoId,task.uploadSessionId) },onOpenVideo?.let { { it(task.videoId) } },
                        onRevealDelete=if(onDeleteClick!=null) ({ revealed=!revealed }) else null,thumbnailLoader=thumbnailLoader,processing=processing[task.videoId])
                    }
                }
            }
        }
    }
}

@Composable
fun UploadTaskCard(task: UploadTaskEntity,onPauseClick: ()->Unit,onResumeClick: ()->Unit,onRetryClick: ()->Unit,
    onCancelClick: ()->Unit,onViewVideo: (()->Unit)?=null,onRevealDelete: (()->Unit)?=null,
    thumbnailLoader: com.example.vidoora.feature.playback.PrivateThumbnailLoader?=null,processing: QueueProcessing?=null) {
    val ready=processing?.publicationState=="PROCESSED"
    var menu by remember { mutableStateOf(false) }
    val active=task.status in setOf("QUEUED","UPLOADING","RETRYING","PAUSED","FAILED")
    ReferenceCard {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().heightIn(min=104.dp)) {
                    UploadPreview(task,thumbnailLoader,Modifier.fillMaxSize())
                    if(task.status=="UPLOADED" && processing?.publicationState=="PROCESSING") {
                        val percent=processing.processingPercent
                        if(percent!=null) LinearProgressIndicator(progress={percent.coerceIn(0,99)/100f},modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        else LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                    }
                    if(task.status !in setOf("UPLOADED","CANCELLED","DELETING")) LinearProgressIndicator(
                        progress={task.progressPercentage.coerceIn(0,100)/100f},modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(when(task.status) { "UPLOADED"->when(processing?.publicationState) {"PROCESSED"->"Ready";"PROCESSING"->"Processing ${processing.processingPercent?.coerceIn(0,99)?.let {"$it%"} ?: ""}";"PROCESSING_FAILED"->"Processing failed";else->"Queued"}; "COMPLETING"->"Finishing"; else->task.status.lowercase().replaceFirstChar { it.uppercase() } },
                            modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,
                            color=if(task.status=="UPLOADED") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            IconButton(onClick={menu=true},modifier=Modifier.size(40.dp)) { UiIcon(R.drawable.ic_ui_more,"Upload options") }
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                if(active) DropdownMenuItem(text={Text("Cancel upload")},onClick={menu=false;onCancelClick()})
                                if(onRevealDelete!=null) DropdownMenuItem(text={Text("Delete video")},onClick={menu=false;onRevealDelete()})
                            }
                        }
                    }
                    Text(task.title,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    val action=when(task.status) { "UPLOADED"->if(ready) "Share" else "Progress"; "PAUSED"->"Resume"; "FAILED"->"Retry"; "CANCELLING"->"Retry cancel"; "DELETING"->"Retry delete"; "QUEUED","UPLOADING","RETRYING"->"Pause"; else->null }
                    if(action!=null) Button(shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth(),enabled=task.status!="UPLOADED" || onViewVideo!=null,
                        onClick={ when(task.status) { "UPLOADED"->onViewVideo?.invoke(); "PAUSED"->onResumeClick(); "QUEUED","UPLOADING","RETRYING"->onPauseClick(); else->onRetryClick() } }) {
                        Text(action,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            task.errorMessage?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
        }
    }
}
