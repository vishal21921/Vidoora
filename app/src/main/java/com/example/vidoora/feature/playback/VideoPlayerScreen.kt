package com.example.vidoora.feature.playback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.vidoora.feature.ads.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.example.vidoora.core.network.ApiConfig
import io.ktor.client.HttpClient

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(videoId: String,client: HttpClient,shareToken: String?=null,progressStore: WatchProgressStore,identity: String,currentIdentity: ()->String?,onDetails: (() -> Unit)?=null,viewerAds: ViewerAdsController?=null,maxHeight: Int?=480,adFree: Boolean=false,onPlans: (() -> Unit)?=null,history: WatchHistoryStore?=null,
    analytics: com.example.vidoora.feature.analytics.PlaybackAnalytics?=null,onBack: ()->Unit) {
    val qualityLimit by rememberUpdatedState(maxHeight)
    val withoutAds by rememberUpdatedState(adFree)
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val connected by rememberInternetConnection()
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val scope=rememberCoroutineScope()
    val session=remember(videoId,client,shareToken,identity,progressStore,history) { WatchProgressSession(client,progressStore,identity,currentIdentity,videoId,shareToken,history) }
    val startup by session.state.collectAsState()
    val activity=context.findActivity()
    ViewerAdsSession(viewerAds,startup.viewer && startup.startPositionMs!=null && !startup.unavailable,consentPage=false)
    var fullscreen by rememberSaveable(videoId) { mutableStateOf(false) }
    var landscape by rememberSaveable(videoId) { mutableStateOf(false) }
    var seekFeedback by remember(videoId) { mutableStateOf<Pair<Boolean,Long>?>(null) }
    DisposableEffect(activity) {
        val previous=activity?.requestedOrientation
        onDispose { if(previous!=null) activity?.requestedOrientation=previous }
    }
    LaunchedEffect(fullscreen,landscape,activity) {
        activity?.requestedOrientation=if(fullscreen && landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    LaunchedEffect(seekFeedback) { if(seekFeedback!=null) { delay(700); seekFeedback=null } }
    BackHandler(fullscreen) { fullscreen=false }
    var savedPosition by rememberSaveable(videoId) { mutableLongStateOf(0L) }
    var resumePlaying by rememberSaveable(videoId) { mutableStateOf(true) }
    // -1 is Auto. Scope the saved preference to this video, not every future upload.
    var qualityHeight by rememberSaveable(videoId) { mutableIntStateOf(-1) }
    var qualities by remember(videoId) { mutableStateOf<List<VideoQuality>>(emptyList()) }
    var renderedHeight by remember(videoId) { mutableIntStateOf(0) }
    var qualityMenu by remember(videoId) { mutableStateOf(false) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    val player=remember(videoId,client,shareToken,identity) {
        val meter = DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(750_000L).build()
        ExoPlayer.Builder(context).setBandwidthMeter(meter).build()
    }
    val preview by produceState<android.graphics.Bitmap?>(null, player) {
        value = PrivateThumbnailLoader(client).load(videoId, shareToken)
    }
    var firstFrame by remember(player) { mutableStateOf(false) }
    var buffering by remember(player) { mutableStateOf(false) }
    var reconnectable by remember(player) { mutableStateOf(false) }
    var autoRetried by remember(player) { mutableStateOf(false) }
    var retryPosition by remember(player) { mutableStateOf<Long?>(null) }
    var openedOffline by remember(player) { mutableStateOf(false) }
    var stalledOffline by remember(player) { mutableStateOf(false) }
    val videoAds=remember(player) { VideoAdSession(context) }
    var playingAd by remember(player) { mutableStateOf(false) }
    val watchClock=remember(player) { VideoWatchAdClock() }
    val analyticsClock=remember(player) { VideoWatchAdClock() }
    var videoBreakActive by remember(player) { mutableStateOf(false) }
    var fullscreenBeforeBreak by remember(player) { mutableStateOf(false) }
    fun watchingContent()=player.isPlaying && !player.isPlayingAd && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && currentIdentity()==identity
    fun flushAnalytics() {
        val now = android.os.SystemClock.elapsedRealtime()
        analyticsClock.update(now, false)
        val watched = analyticsClock.watchedMs
        analyticsClock.reset(now)
        analytics?.flush(videoId, shareToken, identity, watched,
            player.playbackState == Player.STATE_ENDED && !player.isPlayingAd)
    }
    LaunchedEffect(player, session, analytics) {
        if (analytics == null) return@LaunchedEffect
        analytics.open(videoId, shareToken, identity)
        var nextReport = android.os.SystemClock.elapsedRealtime()
        var started = false
        var ended = false
        while (true) {
            delay(500)
            val now = android.os.SystemClock.elapsedRealtime()
            val playing = watchingContent()
            analyticsClock.update(now, playing)
            if (playing && !started) {
                analytics.tick(videoId, shareToken, identity, 0, false)
                started = true
                nextReport = now + 10_000
            }
            val finished = player.playbackState == Player.STATE_ENDED && !player.isPlayingAd
            if (started && (now >= nextReport || (finished && !ended))) {
                val accumulated = analyticsClock.watchedMs
                analyticsClock.reset(now)
                analyticsClock.update(now, playing)
                analytics.tick(videoId, shareToken, identity, accumulated, finished)
                nextReport = android.os.SystemClock.elapsedRealtime() + 10_000
                ended = finished
            }
        }
    }
    fun finishVideoBreak() { if(videoBreakActive) { videoBreakActive=false; fullscreen=fullscreenBeforeBreak } }
    fun applyQuality(tracks: Tracks) {
        if(player.isPlayingAd) return
        val available=VideoQualities.available(tracks,qualityLimit)
        qualities=available
        // Do not forget a saved selection during the temporary empty-track loading state.
        if(tracks.groups.any { it.type==C.TRACK_TYPE_VIDEO }) {
            if(qualityHeight>0 && available.none { it.height==qualityHeight }) qualityHeight=-1
            VideoQualities.apply(player,tracks,qualityHeight.takeIf { it>0 },qualityLimit)
        }
    }
    var checkpointReady by remember(player) { mutableStateOf(false) }
    var timelineChecked by remember(player) { mutableStateOf(false) }
    fun prepare(position: Long) {
        checkpointReady=false; timelineChecked=false; savedPosition=position
        qualities=emptyList(); renderedHeight=0; qualityMenu=false
        val uri=ApiConfig.BASE_URL.trimEnd('/')+"/api/v1/videos/$videoId/playback/master.m3u8"
        val media=MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        val content=HlsMediaSource.Factory(AuthenticatedMediaDataSource.Factory(client,shareToken)).createMediaSource(media)
        val permission=viewerAds?.let { it.enabled && it.state.value.allowed && !it.state.value.privacyBusy }==true
        error=null
        player.setMediaSource(videoAds.wrap(content,player,permission), position)
        player.prepare()
        player.playWhenReady=resumePlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
    DisposableEffect(player,lifecycle,session) {
        val listener=object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if(state==Player.STATE_READY && !player.isPlayingAd) {
                    if(!timelineChecked) {
                        timelineChecked=true
                        val target=session.timelinePosition(savedPosition,player.contentDuration)
                        if(target!=savedPosition) { savedPosition=target; player.seekTo(target) }
                    }
                    session.mediaPrepared(); checkpointReady=true; finishVideoBreak()
                }
                if(state==Player.STATE_ENDED && !player.isPlayingAd) session.checkpoint(player.contentPosition,player.contentDuration,ended=true)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean,reason: Int) {
                if(!playWhenReady && checkpointReady && !player.isPlayingAd) session.checkpoint(player.contentPosition,player.contentDuration,player.playbackState==Player.STATE_ENDED)
            }
            override fun onEvents(player: Player,events: Player.Events) {
                analyticsClock.update(android.os.SystemClock.elapsedRealtime(), watchingContent())
                watchClock.update(android.os.SystemClock.elapsedRealtime(),watchingContent() && !withoutAds)
                playingAd=player.isPlayingAd
                if(playingAd) { qualityMenu=false; seekFeedback=null }
                // Ad/content transitions can stay READY without a playback-state callback.
                if(!player.isPlayingAd && player.playbackState==Player.STATE_READY) {
                    if(!timelineChecked) {
                        timelineChecked=true
                        val target=session.timelinePosition(savedPosition,player.contentDuration)
                        if(target!=savedPosition) { savedPosition=target; player.seekTo(target) }
                    }
                    session.mediaPrepared(); checkpointReady=true; finishVideoBreak()
                }
                if (watchingContent()) session.contentPlaying()
            }
            override fun onTracksChanged(tracks: Tracks) { applyQuality(tracks) }
            override fun onVideoSizeChanged(videoSize: VideoSize) { renderedHeight=videoSize.height.coerceAtLeast(0) }
            override fun onRenderedFirstFrame() { if (!player.isPlayingAd) firstFrame = true }
            override fun onPlayerError(exception: PlaybackException) {
                savedPosition=player.contentPosition; resumePlaying=player.playWhenReady
                retryPosition=savedPosition
                val causes = generateSequence<Throwable>(exception) { it.cause }.take(12).toList()
                val httpStatus = causes.filterIsInstance<PlaybackHttpFailure>().firstOrNull()?.status
                reconnectable = when (httpStatus) {
                    null -> !connected || causes.any { it is java.net.SocketException || it is java.net.SocketTimeoutException ||
                        it is java.net.UnknownHostException || it is io.ktor.client.plugins.HttpRequestTimeoutException }
                    408, 502, 503, 504 -> true
                    else -> false
                }
                buffering=false
                checkpointReady=false; session.mediaFailed(); finishVideoBreak()
                error="Unable to play this video. Check the link and connection, then retry."
            }
        }
        val observer=LifecycleEventObserver { _,event ->
            when(event) {
                Lifecycle.Event.ON_PAUSE -> {
                    watchClock.update(android.os.SystemClock.elapsedRealtime(),false)
                    flushAnalytics()
                }
                Lifecycle.Event.ON_RESUME -> {
                    analyticsClock.update(android.os.SystemClock.elapsedRealtime(),watchingContent())
                    watchClock.update(android.os.SystemClock.elapsedRealtime(),watchingContent() && !withoutAds)
                    if (watchingContent()) session.contentPlaying()
                }
                Lifecycle.Event.ON_STOP -> {
                    analyticsClock.update(android.os.SystemClock.elapsedRealtime(),false)
                    if (error==null && !session.state.value.loading) {
                        savedPosition=player.contentPosition
                        resumePlaying=player.playWhenReady
                    }
                    if(checkpointReady && !player.isPlayingAd) session.checkpoint(player.contentPosition,player.contentDuration,player.playbackState==Player.STATE_ENDED)
                    player.pause()
                }
                Lifecycle.Event.ON_START -> if(session.state.value.startPositionMs!=null) player.playWhenReady=resumePlaying
                else -> Unit
            }
        }
        player.addListener(listener); lifecycle.addObserver(observer)
        onDispose {
            watchClock.update(android.os.SystemClock.elapsedRealtime(),false)
            flushAnalytics()
            if(checkpointReady && !player.isPlayingAd) session.checkpoint(player.contentPosition,player.contentDuration,player.playbackState==Player.STATE_ENDED)
            savedPosition=player.contentPosition
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) resumePlaying=player.playWhenReady
            lifecycle.removeObserver(observer); player.removeListener(listener); player.release(); videoAds.release()
        }
    }
    LaunchedEffect(session) {
        error=null
        openedOffline=!connected
        session.open()
    }
    LaunchedEffect(session,startup.startPositionMs,startup.attempt) { startup.startPositionMs?.let { prepare(it) } }
    LaunchedEffect(player,session) {
        while(true) {
            delay(5_000)
            if(checkpointReady && player.isPlaying && !player.isPlayingAd) session.checkpoint(player.contentPosition,player.contentDuration)
        }
    }
    LaunchedEffect(player,session) {
        while(true) {
            delay(1_000)
            watchClock.update(android.os.SystemClock.elapsedRealtime(),watchingContent() && !withoutAds)
            val permission=viewerAds?.let { it.enabled && it.state.value.allowed && !it.state.value.privacyBusy }==true
            if(checkpointReady && !videoBreakActive && permission && watchClock.takeBreak(player.contentDuration-player.contentPosition)) {
                val position=player.contentPosition
                session.checkpoint(position,player.contentDuration)
                resumePlaying=player.playWhenReady; checkpointReady=false
                watchClock.update(android.os.SystemClock.elapsedRealtime(),false)
                fullscreenBeforeBreak=fullscreen; videoBreakActive=true; fullscreen=true
                player.stop(); player.clearMediaItems()
                videoAds.beginBreak()
                prepare(position)
            }
        }
    }
    var previousLimit by remember(player) { mutableStateOf(maxHeight) }
    LaunchedEffect(maxHeight,adFree) {
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE,maxHeight ?: Int.MAX_VALUE).build()
        applyQuality(player.currentTracks)
        if(adFree) { watchClock.reset(android.os.SystemClock.elapsedRealtime()); videoAds.cancelBreak() }
        if(adFree && player.currentMediaItem!=null) {
            val position=player.contentPosition
            if(!player.isPlayingAd) resumePlaying=player.playWhenReady
            player.stop(); player.clearMediaItems()
            finishVideoBreak(); prepare(position)
        } else if(previousLimit!=maxHeight && checkpointReady) {
            val position=player.contentPosition
            resumePlaying=player.playWhenReady
            player.stop(); player.clearMediaItems(); prepare(position)
        }
        previousLimit=maxHeight
    }
    fun retry(manual: Boolean = true) {
        if(startup.loading) return
        if (manual) autoRetried=false
        if (player.playbackState == Player.STATE_BUFFERING && !player.isPlayingAd) {
            retryPosition=player.contentPosition
            resumePlaying=player.playWhenReady
            if (checkpointReady) session.checkpoint(player.contentPosition,player.contentDuration)
            checkpointReady=false
            session.mediaFailed()
            player.stop()
        }
        reconnectable=false
        stalledOffline=false
        openedOffline=!connected
        scope.launch {
            error=null
            session.open(retryPosition)
        }
    }
    LaunchedEffect(connected, buffering, playingAd, startup.loading, startup.error, startup.unavailable, error, reconnectable, lifecycleState) {
        if (!buffering || playingAd) stalledOffline = false
        else if (!connected) stalledOffline = true
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED) || startup.loading || startup.unavailable) return@LaunchedEffect
        if (!connected && startup.error != null) openedOffline = true
        val stalled = stalledOffline && buffering && error == null && !playingAd
        if (connected && !autoRetried && (reconnectable || stalled || (openedOffline && startup.error != null))) {
            delay(600)
            autoRetried = true
            retry(manual = false)
        }
    }
    LaunchedEffect(checkpointReady, buffering, error, startup.loading) {
        if (checkpointReady && !buffering && error == null && !startup.loading) {
            delay(10_000)
            autoRetried=false
            retryPosition=null
        }
    }
    var showBuffering by remember(player) { mutableStateOf(false) }
    LaunchedEffect(buffering) {
        showBuffering=false
        if (buffering) {
            delay(350)
            showBuffering=true
        }
    }
    val failure = error ?: startup.error
    val waitingForConnection = !connected && (buffering || startup.loading || reconnectable || startup.error != null)
    val loadingMessage = when {
        playingAd || startup.promptPositionMs != null -> null
        waitingForConnection -> "Reconnecting…"
        failure != null -> null
        startup.loading && !firstFrame -> "Checking video access…"
        startup.loading || (showBuffering && firstFrame) -> "Buffering…"
        !firstFrame -> "Starting video…"
        else -> null
    }
    if(startup.unavailable && !startup.loading && startup.startPositionMs==null) {
        VideoUnavailableScreen(onBack)
        return
    }
    startup.promptPositionMs?.let { position ->
        AlertDialog(onDismissRequest=onBack,title={ Text("Continue watching?") },
            text={ Text("Resume from ${WatchProgressPolicy.time(position)} or start from the beginning.") },
            confirmButton={ TextButton(onClick={ resumePlaying=true; session.resume() }) { Text("Resume") } },
            dismissButton={ TextButton(onClick={ scope.launch { resumePlaying=true; session.startOver() } }) { Text("Start over") } })
    }
    val showPreview = !firstFrame && !playingAd
    val hasMedia = startup.startPositionMs!=null || player.currentMediaItem!=null
    if(fullscreen && hasMedia) {
        FullscreenVideo({ fullscreen=false }) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { landscape = !landscape }, enabled = !playingAd) {
                        Icon(androidx.compose.ui.res.painterResource(com.example.vidoora.R.drawable.ic_ui_rotate),
                            if (landscape) "Rotate to portrait" else "Rotate to landscape", tint = Color.White)
                    }
                    IconButton(onClick = { fullscreen = false }) {
                        Icon(androidx.compose.ui.res.painterResource(androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_exit),
                            "Exit fullscreen", tint = Color.White)
                    }
                }
                PlayerSurface(player,true,{ fullscreen=false },{ forward -> seekFeedback=forward to System.nanoTime() },seekFeedback?.first,
                    Modifier.fillMaxWidth().weight(1f),videoAds,hasMedia,preview,showPreview,loadingMessage,failure,{ retry() })
            }
        }
    }
    Scaffold(topBar={ TopAppBar(title={ Text("Watch video") },navigationIcon={ IconButton(onClick=onBack) { com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_back,"Back") } },actions={
        Box {
            TextButton(onClick={ qualityMenu=true },enabled=!playingAd && qualities.isNotEmpty() && error==null && startup.startPositionMs!=null && !startup.loading) {
                Text("Quality: "+if(qualityHeight>0) "${qualityHeight}p" else "Auto")
            }
            DropdownMenu(expanded=qualityMenu,onDismissRequest={ qualityMenu=false }) {
                DropdownMenuItem(text={ Text((if(qualityHeight<0) "✓ " else "")+"Auto") },onClick={
                    qualityHeight=-1; qualityMenu=false; applyQuality(player.currentTracks)
                })
                qualities.forEach { option -> DropdownMenuItem(text={ Text((if(qualityHeight==option.height) "✓ " else "")+option.label) },onClick={
                    qualityHeight=option.height; qualityMenu=false; applyQuality(player.currentTracks)
                }) }
            }
        }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if(!fullscreen) {
                PlayerSurface(player,false,{ landscape=false; fullscreen=true },{ forward -> seekFeedback=forward to System.nanoTime() },seekFeedback?.first,
                    Modifier.fillMaxWidth().aspectRatio(16f/9f),videoAds,hasMedia,preview,showPreview,loadingMessage,failure,{ retry() })
                Text("Double-tap left or right to seek 10 seconds.",modifier=Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)
                if(onDetails!=null) TextButton(onClick=onDetails,modifier=Modifier.padding(horizontal=8.dp)) { Text("Video details") }
            }
            if(!fullscreen) ViewerBanner(viewerAds,startup.viewer && checkpointReady && error==null && !startup.loading && !playingAd)
            if(!fullscreen && !adFree && onPlans!=null && checkpointReady && error==null && !startup.loading && !playingAd)
                com.example.vidoora.feature.membership.MembershipEntryCard(onClick=onPlans,
                    modifier=Modifier.padding(horizontal=16.dp,vertical=12.dp))
            startup.warning?.let { Text(it,modifier=Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall) }
            if(renderedHeight>0 && error==null) Text("Playing: ${renderedHeight}p",modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
            if(qualityHeight>0 && error==null) Text("Quality changes after buffered video plays.",modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
        }
    }
}




private tailrec fun Context.findActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@UnstableApi
@Composable
private fun PlayerSurface(player: ExoPlayer,fullscreen: Boolean,onFullscreen: ()->Unit,onSeek: (Boolean)->Unit,feedback: Boolean?,
    modifier: Modifier,videoAds: VideoAdSession,hasMedia: Boolean,preview: android.graphics.Bitmap?,showPreview: Boolean,
    loadingMessage: String?,failure: String?,onRetry: ()->Unit) {
    val toggle by rememberUpdatedState(onFullscreen)
    val seek by rememberUpdatedState(onSeek)
    Box(modifier.background(Color.Black),contentAlignment=Alignment.Center) {
        if (hasMedia) AndroidView(factory={ context -> GesturePlayerView(context).apply {
            this.player=player
            videoAds.attach(adViewGroup)
            useController=true
            controllerShowTimeoutMs=3_000
            controllerAutoShow=true
            findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                setPlayedColor(android.graphics.Color.rgb(255, 130, 113))
                setScrubberColor(android.graphics.Color.WHITE)
                setBufferedColor(0x99FFFFFF.toInt())
                setUnplayedColor(0x44FFFFFF)
            }
            setFullscreenButtonClickListener { toggle() }
            onSeekFeedback={ seek(it) }
            for(id in listOf(androidx.media3.ui.R.id.exo_fullscreen,androidx.media3.ui.R.id.exo_minimal_fullscreen)) {
                findViewById<ImageView>(id)?.apply {
                    setImageResource(if(fullscreen) androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_exit else androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_enter)
                    contentDescription=if(fullscreen) "Exit fullscreen" else "Fullscreen"
                }
            }
        } },onRelease={ videoAds.detach(it.adViewGroup); it.player=null },modifier=Modifier.fillMaxSize())
        AnimatedVisibility(visible=showPreview && preview!=null, enter=fadeIn(), exit=fadeOut(), modifier=Modifier.matchParentSize()) {
            preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale=ContentScale.Fit) }
        }
        if (loadingMessage != null || failure != null) {
            Column(Modifier.padding(12.dp).background(Color.Black.copy(alpha=.8f), MaterialTheme.shapes.medium).padding(12.dp),
                horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if (loadingMessage != null) {
                    CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                    Text(loadingMessage, color=Color.White, style=MaterialTheme.typography.bodySmall)
                }
                if (failure != null) {
                    if (loadingMessage == null) Text(failure, color=Color.White, style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=onRetry) { Text("Retry",color=Color.White) }
                }
            }
        }
        var lastDirection by remember { mutableStateOf(false) }
        if (feedback != null) SideEffect { lastDirection=feedback }
        val forward=feedback ?: lastDirection
        AnimatedVisibility(feedback!=null, Modifier.align(if(forward) Alignment.CenterEnd else Alignment.CenterStart).padding(24.dp),
            enter=fadeIn(),exit=fadeOut()) {
            Text(if(forward) "+10 seconds" else "−10 seconds",color=Color.White,
                modifier=Modifier.background(Color.Black.copy(alpha=.72f),CircleShape).padding(12.dp))
        }
    }
}

@Composable
private fun FullscreenVideo(onExit: ()->Unit,content: @Composable ()->Unit) {
    Dialog(onDismissRequest=onExit,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        DisposableEffect(view) {
            val window=(view.parent as? DialogWindowProvider)?.window
            val controller=window?.let { WindowCompat.getInsetsController(it,view) }
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        content()
    }
}













