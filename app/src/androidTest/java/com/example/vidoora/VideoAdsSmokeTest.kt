package com.example.vidoora

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vidoora.feature.ads.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network check: Google's public sample content/demo ad, never user videos or credentials. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoAdsSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun armedVideoBreakOrAdFailureContinuesIntoPublicSampleContent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("videoAdsSmoke")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val consent=freeAdsController(context)
        val adStarted=AtomicBoolean(false); val contentReady=AtomicBoolean(false); val failure=AtomicInteger(0)
        lateinit var player: ExoPlayer
        lateinit var ads: VideoAdSession
        compose.activityRule.scenario.onActivity {
            // Headless Goldfish hardware H264 failed; exercise the SDK with Android software codecs.
            val renderers=DefaultRenderersFactory(compose.activity).forceDisableMediaCodecAsynchronousQueueing()
                .setMediaCodecSelector { mime,secure,tunnel -> MediaCodecUtil.getDecoderInfos(mime,secure,tunnel).filter { it.softwareOnly } }
            player=ExoPlayer.Builder(compose.activity,renderers).build()
            ads=VideoAdSession(compose.activity)
            player.addListener(object: Player.Listener {
                override fun onEvents(p: Player,events: Player.Events) {
                    if(p.playbackState==Player.STATE_READY) {
                        if(p.isPlayingAd) { if(p.isPlaying) adStarted.set(true) } else contentReady.set(true)
                    }
                }
                override fun onPlayerError(error: PlaybackException) { failure.set(error.errorCode) }
            })
        }
        compose.setContent {
            ViewerAdsSession(consent,true,consentPage=false)
            AndroidView(factory={ PlayerView(it).also { view -> view.player=player; ads.attach(view.adViewGroup) } },modifier=Modifier.fillMaxSize())
            DisposableEffect(player) { onDispose { player.release(); ads.release() } }
        }
        compose.waitForIdle()
        val consentDeadline=SystemClock.elapsedRealtime()+45_000
        while(consent.state.value.privacyBusy && SystemClock.elapsedRealtime()<consentDeadline) SystemClock.sleep(250)
        assumeTrue("No SDK consent permission: network check skipped without forcing consent",consent.state.value.allowed)
        compose.runOnIdle {
            val content=DefaultMediaSourceFactory(compose.activity).createMediaSource(MediaItem.fromUri("https://storage.googleapis.com/gvabox/media/samples/stock.mp4"))
            assertSame("Entry must have no in-player pre-roll",content,ads.wrap(content,player,true))
            ads.beginBreak() // Explicit test break; production arms only after ten minutes actually watched.
            player.setMediaSource(ads.wrap(content,player,true))
            player.prepare(); player.playWhenReady=true
        }
        val deadline=SystemClock.elapsedRealtime()+90_000
        while(!contentReady.get() && failure.get()==0 && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(250)
        val folder=File(context.getExternalFilesDir(null),"video-ads-smoke").apply { mkdirs() }
        File(folder,"outcome.txt").writeText("consentAllowed=true; adStarted=${adStarted.get()}; contentReady=${contentReady.get()}; playerError=${failure.get()}")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder,"screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        assertEquals("Public sample player failed",0,failure.get())
        assertTrue("No content-ready callback within network test bound",contentReady.get())
    }
}






