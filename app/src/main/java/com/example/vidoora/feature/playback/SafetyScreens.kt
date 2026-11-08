package com.example.vidoora.feature.playback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vidoora.ui.theme.*
import com.example.vidoora.R

@Composable
fun BlockedUploadersScreen(state: BlockedUploadersState,onRefresh: ()->Unit,onMore: ()->Unit,onUnblock: (String)->Unit,onBack: ()->Unit) {
    var target by remember { mutableStateOf<BlockedUploaderDto?>(null) }
    target?.let { uploader -> AlertDialog(onDismissRequest={ target=null },title={ Text("Unblock ${uploader.displayName}?") },
        text={ Text("Watching still requires an active video link.") },
        confirmButton={ TextButton(onClick={ target=null; onUnblock(uploader.id) },enabled=!state.loading) { Text("Unblock") } },
        dismissButton={ TextButton(onClick={ target=null }) { Text("Cancel") } }) }
    Scaffold(topBar={ Column {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=4.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            IconButton(onClick=onBack) { UiIcon(R.drawable.ic_ui_back,"Back") }
            IconButton(onClick=onRefresh,enabled=!state.loading) { UiIcon(R.drawable.ic_ui_refresh,"Refresh") }
        }
        Column(Modifier.readableWidth(640.dp).padding(horizontal=16.dp).padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("Blocked uploaders",style=MaterialTheme.typography.headlineSmall)
            Text("Manage uploaders you've blocked.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
    } }) { padding ->
        LazyColumn(Modifier.fillMaxHeight().padding(padding).readableWidth(640.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            if(state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Box(Modifier.padding(16.dp)) { FormError(it) } } }
            if(!state.loading && state.items.isEmpty() && state.error==null) item {
                Text("No blocked uploaders.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(24.dp))
            }
            items(state.items,key={ it.id }) { uploader ->
                Row(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(horizontal=16.dp,vertical=12.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primary,modifier=Modifier.size(42.dp)) {
                        Box(contentAlignment=Alignment.Center) {
                            val initials=uploader.displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
                            Text(initials.ifEmpty { "?" },style=MaterialTheme.typography.titleMedium)
                        }
                    }
                    Text(uploader.displayName,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
                    FilledTonalButton(onClick={ target=uploader },enabled=!state.loading,shape=RoundedCornerShape(8.dp),
                        contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
                        colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.surfaceContainer,contentColor=MaterialTheme.colorScheme.onSurface),
                        modifier=Modifier.heightIn(min=48.dp)) { Text("Unblock",style=MaterialTheme.typography.labelMedium) }
                }
                HorizontalDivider(Modifier.padding(start=70.dp,end=16.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
            }
            if(state.nextCursor!=null) item { OutlinedButton(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick=onMore,enabled=!state.loading,modifier=Modifier.padding(16.dp)) { Text("Load more") } }
        }
    }
}

