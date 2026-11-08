package com.example.vidoora.feature.playback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.vidoora.ui.theme.readableWidth
import androidx.compose.ui.Modifier
import com.example.vidoora.ui.theme.*
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLibraryScreen(state: VideoLibraryState,onRefresh: ()->Unit,onPlay: (String)->Unit,onBack: ()->Unit,onSearchText: (String)->Unit,onSearch: ()->Unit,onMore: ()->Unit,thumbnailLoader: PrivateThumbnailLoader,onSort: (String)->Unit,onStatus: (String)->Unit,onSharing: (String)->Unit,onReset: ()->Unit,folders: FoldersState,onFolder: (String)->Unit,onManageFolders: ()->Unit,onVisibleVideos: (List<String>)->Unit = {}) {
    val listState=rememberLazyListState()
    var showFilters by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(listState) { snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }.distinctUntilChanged().collect { onVisibleVideos(it) } }
    Scaffold(topBar={ TopAppBar(title={ Text("My Videos") },navigationIcon={ IconButton(onClick=onBack) { com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_back,"Back") } },
        actions={ IconButton(onClick=onRefresh,enabled=!state.loading) { com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_refresh,"Refresh") } }) }) { padding ->
        LazyColumn(state=listState,modifier=Modifier.fillMaxHeight().padding(padding).readableWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(value=state.searchText,onValueChange=onSearchText,label={ Text("Search your videos") },
                    supportingText={ Text("Title or description · Up to 100 characters") },singleLine=true,modifier=Modifier.fillMaxWidth())
                Button(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick=onSearch) { Text("Search") }
                TextButton(onClick={ showFilters=!showFilters }) { Text(if(showFilters) "Hide filters" else if(state.sortOrder!="NEWEST" || state.processingFilter!="ALL" || state.sharingFilter!="ALL" || state.folderFilter!="ALL") "Sort & filters · Active" else "Sort & filters") }
                if(showFilters) {
                LibraryChoice("Folder",state.folderFilter,listOf("ALL" to "All videos","UNFILED" to "Unfiled")+folders.items.map { it.id to "Folder: ${it.name}" },onFolder)
                TextButton(onClick=onManageFolders) { Text("Manage folders") }
                folders.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                LibraryChoice("Sort",state.sortOrder,listOf("NEWEST" to "Newest","OLDEST" to "Oldest"),onSort)
                LibraryChoice("Status",state.processingFilter,listOf("ALL" to "All statuses","READY" to "Ready","PROCESSING" to "Waiting / processing","FAILED" to "Failed"),onStatus)
                LibraryChoice("Sharing",state.sharingFilter,listOf("ALL" to "All sharing","PRIVATE" to "Private","SHARED" to "Shared by link"),onSharing)
                if(state.appliedSearch.isNotEmpty() || state.sortOrder!="NEWEST" || state.processingFilter!="ALL" || state.sharingFilter!="ALL" || state.folderFilter!="ALL") TextButton(onClick=onReset) { Text("Reset view") }
                }
                if(state.appliedSearch.isNotEmpty()) Text("Results for: ${state.appliedSearch}",style=MaterialTheme.typography.bodySmall)
            }
            if(state.loading) item { LinearProgressIndicator(modifier=Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(error,color=MaterialTheme.colorScheme.error) } }
            state.statusError?.let { error -> item { Text(error,color=MaterialTheme.colorScheme.error) } }
            if(!state.loading && state.error==null && state.videos.isEmpty()) item { Text(if(state.appliedSearch.isEmpty() && state.processingFilter=="ALL" && state.sharingFilter=="ALL" && state.folderFilter=="ALL") "Your uploaded videos will appear here." else "No videos match this search and filters.") }
            items(state.videos,key={ it.id }) { video ->
                Card(modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(10.dp).height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().heightIn(min=104.dp)) { VideoThumbnail(video,thumbnailLoader,Modifier.fillMaxSize()) }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(video.title,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if(video.publicationState!="PROCESSED") Text(VideoProcessingStatus.label(video.publicationState),style=MaterialTheme.typography.bodySmall)
                        Text(if(video.published && video.visibility=="UNLISTED") "Shared by link" else "Private",style=MaterialTheme.typography.bodySmall)
                        
                        Button(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick={ onPlay(video.id) }) { Text("View video") }
                    }
                    }
                }
            }
            if(state.nextCursor!=null) item { OutlinedButton(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick=onMore,enabled=!state.loading) { Text("Load more") } }
        }
    }
}





@Composable
private fun LibraryChoice(label: String,value: String,choices: List<Pair<String,String>>,onSelect: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick={ expanded=true },modifier=Modifier.fillMaxWidth()) { Text("$label: ${choices.firstOrNull { it.first==value }?.second ?: value}") }
        DropdownMenu(expanded=expanded,onDismissRequest={ expanded=false }) {
            choices.forEach { (id,title) -> DropdownMenuItem(text={ Text((if(id==value) "✓ " else "")+title) },onClick={ expanded=false; onSelect(id) }) }
        }
    }
}





