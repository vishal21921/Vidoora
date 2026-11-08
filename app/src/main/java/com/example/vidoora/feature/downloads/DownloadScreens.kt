package com.example.vidoora.feature.downloads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.example.vidoora.feature.playback.GesturePlayerView
import com.example.vidoora.ui.theme.*
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivateDownloadsScreen(items: List<OfflineItem>,account: String?,onBack: ()->Unit,onLogin: ()->Unit,onPlans: ()->Unit,
    onPlay: (OfflineItem)->Unit,onPause: (OfflineItem)->Unit,onResume: (OfflineItem)->Unit,onRemove: (OfflineItem)->Unit,error: String?=null,quota: DownloadQuotaDto?=null) {
    val context=LocalContext.current
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){}
    val downloading=items.any{it.account==account && it.state in listOf("QUEUED","DOWNLOADING")}
    LaunchedEffect(downloading){if(downloading && android.os.Build.VERSION.SDK_INT>=33 &&
        androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)}
    var removing by remember(account){mutableStateOf<OfflineItem?>(null)}
    removing?.let {item->AlertDialog(onDismissRequest={removing=null},title={Text("Remove download?")},
        text={Text("Removes this device's offline copy. Your original video stays safe. Used download allowances are not refunded.")},
        confirmButton={TextButton(onClick={removing=null;onRemove(item)}){Text("Remove")}},dismissButton={TextButton(onClick={removing=null}){Text("Keep")}})}
    Scaffold(topBar={ReferenceHeader("Downloads",onBack)}) {padding->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth(640.dp).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(account==null) PrimaryAction("Log in for downloads",onLogin)
            else {
                if(quota==null) Text("Download allowance unavailable — reconnect to check")
                else {
                    Text(if (quota.limit == Int.MAX_VALUE) "Unlimited admin downloads" else "${quota.remaining} of ${quota.limit} downloads left",style=MaterialTheme.typography.titleMedium)
                    Text("${quota.used} used · ${quota.reserved} in progress",style=MaterialTheme.typography.bodySmall)
                    if(quota.limit>0 && quota.remaining==0) Text("Allowance reached. New downloads are blocked for this period.")
                }
                Text("Private offline videos",style=MaterialTheme.typography.titleMedium)
                Text(if (quota?.limit == Int.MAX_VALUE) "Admin downloads have no count limit. Reconnect every 24 hours or after a restart to validate access."
                    else "Week: 5. Monthly: 30 per billing period. Access lasts up to 24 hours and ends with your plan. Reconnect after a restart.",style=MaterialTheme.typography.bodySmall)
                if (quota?.limit != Int.MAX_VALUE) TextButton(onClick=onPlans){Text("View plans")}
                error?.let{FormError(it)}
                val own=items.filter{it.account==account}
                if(own.isEmpty()) Text("No downloads yet. Open a video link and choose Download.")
                own.forEach {item->ReferenceCard {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(item.manifest?.title ?: "Preparing download",style=MaterialTheme.typography.titleMedium,maxLines=2)
                    Text(when(item.state){"COMPLETE"->"Saved privately";"DOWNLOADING"->"Downloading";"QUEUED"->"Queued / waiting for connection";"PAUSED"->"Paused";else->"Needs attention"},style=MaterialTheme.typography.bodySmall)
                    if(item.state in listOf("DOWNLOADING","QUEUED")) {
                        val total=item.manifest?.paths?.size ?: 0
                        if(total>0) LinearProgressIndicator(progress={item.hashes.size.toFloat()/total},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    item.message?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(item.state=="COMPLETE") TextButton(onClick={onPlay(item)}){Text("Watch offline")}
                        if(item.state in listOf("QUEUED","DOWNLOADING")) TextButton(onClick={onPause(item)}){Text("Pause")}
                        if(item.state in listOf("PAUSED","FAILED")) TextButton(onClick={onResume(item)}){Text("Retry / Resume")}
                        TextButton(onClick={removing=item}){Text("Remove")}
                    }
                }}}
            }
        }
    }
}
private fun Context.activity(): Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.activity();else->null}
private fun connected(context: Context): Boolean=runCatching {
    val manager=context.getSystemService(ConnectivityManager::class.java)
    manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
}.getOrDefault(false)

@UnstableApi
@Composable
fun PrivateOfflinePlayer(item: OfflineItem,repository: DownloadRepository,onBack: ()->Unit) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle;val activity=context.activity()
    val player=remember(item.id){ExoPlayer.Builder(context).build()}
    var error by remember(item.id){mutableStateOf<String?>(null)}
    var prepared by remember(item.id){mutableStateOf(false)}
    var full by remember(item.id){mutableStateOf(false)}
    var validating by remember(item.id){mutableStateOf(true)}
    BackHandler(full){full=false}
    DisposableEffect(activity){val previous=activity?.requestedOrientation;onDispose{if(previous!=null) activity?.requestedOrientation=previous}}
    LaunchedEffect(full){activity?.requestedOrientation=if(full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
    LaunchedEffect(item.id) {
        val valid=repository.validate(item);validating=false
        if(valid) {
            val manifest=item.manifest!!
            val media=MediaItem.Builder().setUri("vidoora-offline://${item.id}/${manifest.entry}").setMimeType(MimeTypes.APPLICATION_M3U8).build()
            player.setMediaSource(HlsMediaSource.Factory(OfflineDataSource.Factory(repository.store,item.account,item.id)).createMediaSource(media))
            player.prepare();player.playWhenReady=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED);prepared=true
        }else error="Connect online to validate this download. Video sharing or plan access may have ended."
        var wasConnected=connected(context)
        while(isActive) {
            delay(1000)
            if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
            val current=withContext(Dispatchers.IO){repository.store.get(item.account,item.id)}
            if(current==null || !repository.store.valid(current)){player.stop();error="Download needs online validation.";break}
            val online=connected(context)
            if(online && !wasConnected && !repository.validate(current)){player.stop();error="Video or plan access is unavailable.";break}
            wasConnected=online
        }
    }
    DisposableEffect(player,lifecycle) {
        val listener=object: Player.Listener{override fun onPlayerError(e: PlaybackException){error="Download unavailable. Reconnect to validate or remove and download again."}}
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE) player.pause()}
        player.addListener(listener);lifecycle.addObserver(observer)
        onDispose{player.removeListener(listener);lifecycle.removeObserver(observer);player.release()}
    }
    @Composable fun surface(modifier: Modifier) {
        AndroidView(factory={GesturePlayerView(it).apply{this.player=player}},update={it.player=player},onRelease={it.player=null},modifier=modifier)
    }
    if(full) Dialog(onDismissRequest={full=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            TextButton(onClick={full=false}){Text("Exit fullscreen")}
            surface(Modifier.fillMaxWidth().weight(1f))
        }
    }else Scaffold(topBar={ReferenceHeader("Offline video",onBack)}) {padding->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth(720.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(validating) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(prepared) surface(Modifier.fillMaxWidth().aspectRatio(16f/9f))
            Text(item.manifest?.title ?: "Offline video",style=MaterialTheme.typography.titleMedium)
            error?.let{FormError(it)}
            if(prepared && error==null) OutlinedButton(onClick={full=true}){Text("Fullscreen")}
            Text("Private in-app copy. Double-tap left or right to seek 10 seconds.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
