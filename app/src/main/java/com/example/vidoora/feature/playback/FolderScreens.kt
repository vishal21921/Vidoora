package com.example.vidoora.feature.playback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.vidoora.ui.theme.readableWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(state: FoldersState,onRefresh: ()->Unit,onSave: (String,String?)->Unit,onRemove: (String)->Unit,onBack: ()->Unit) {
    var editing by remember { mutableStateOf(false) }; var target by remember { mutableStateOf<VideoFolderDto?>(null) }
    var name by remember { mutableStateOf("") }; var removing by remember { mutableStateOf<VideoFolderDto?>(null) }
    LaunchedEffect(state.change) { editing=false; removing=null }
    if(editing) AlertDialog(onDismissRequest={ if(!state.loading) editing=false },title={ Text(if(target==null) "New folder" else "Rename folder") },
        text={ Column { OutlinedTextField(value=name,onValueChange={ name=it.take(64) },label={ Text("Folder name") },enabled=!state.loading); state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) } } },
        confirmButton={ TextButton(onClick={ onSave(name,target?.id) },enabled=!state.loading && name.isNotBlank()) { Text("Save") } },dismissButton={ TextButton(onClick={ editing=false },enabled=!state.loading) { Text("Cancel") } })
    removing?.let { folder -> AlertDialog(onDismissRequest={ if(!state.loading) removing=null },title={ Text("Remove folder?") },
        text={ Column { Text("Remove ${folder.name}? Its videos move to Unfiled. No videos are deleted."); state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) } } },
        confirmButton={ TextButton(onClick={ onRemove(folder.id) },enabled=!state.loading) { Text("Remove folder") } },dismissButton={ TextButton(onClick={ removing=null },enabled=!state.loading) { Text("Cancel") } }) }
    Scaffold(topBar={ TopAppBar(title={ Text("Manage folders") },navigationIcon={ IconButton(onClick=onBack) { com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_back,"Back") } },actions={ IconButton(onClick=onRefresh,enabled=!state.loading) { com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_refresh,"Refresh") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxHeight().padding(padding).readableWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Button(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick={ target=null; name=""; editing=true },enabled=!state.loading && state.loaded) { Text("New folder") }; Text("Private folders · One folder per video · Up to 100 folders") }
            if(state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            if(state.loaded && state.items.isEmpty()) item { Text("No folders yet. Create one, then open your video's details to move it.") }
            items(state.items,key={ it.id }) { folder -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(folder.name,style=MaterialTheme.typography.titleMedium)
                Row { TextButton(onClick={ target=folder; name=folder.name; editing=true },enabled=!state.loading) { Text("Rename") }; TextButton(onClick={ removing=folder },enabled=!state.loading) { Text("Remove") } }
            } } }
        }
    }
}

@Composable
fun MoveVideoDialog(state: VideoDetailsState,onSelect: (String?)->Unit,onSave: ()->Unit,onCancel: ()->Unit,onRetry: ()->Unit) {
    AlertDialog(onDismissRequest={ if(!state.loading) onCancel() },title={ Text("Move to folder") },text={
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            if(state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.moveError?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            if(!state.foldersLoaded && !state.loading) item { TextButton(onClick=onRetry) { Text("Retry") } }
            if(state.foldersLoaded) {
                item { TextButton(onClick={ onSelect(null) },enabled=!state.loading) { Text((if(state.selectedFolder==null) "✓ " else "")+"Unfiled") } }
                items(state.folderChoices,key={ it.id }) { folder -> TextButton(onClick={ onSelect(folder.id) },enabled=!state.loading) { Text((if(state.selectedFolder==folder.id) "✓ " else "")+"Folder: ${folder.name}") } }
            }
        }
    },confirmButton={ TextButton(onClick=onSave,enabled=state.foldersLoaded && !state.loading) { Text("Move") } },dismissButton={ TextButton(onClick=onCancel,enabled=!state.loading) { Text("Cancel") } })
}


