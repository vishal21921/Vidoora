package com.example.vidoora.feature.ads

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.AdViewProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ima.ImaAdsLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import com.example.vidoora.BuildConfig

/** One local watch session. Only Google demo VAST; authenticated content factory is never used for ads. */
@UnstableApi
class VideoAdSession(private val context: Context) {
    val overlay=FrameLayout(context)
    private val provider=object: AdViewProvider {
        override fun getAdViewGroup(): ViewGroup=overlay
    }
    private var loader: ImaAdsLoader?=null
    private var adsId=Any()
    private var xml: String?=null
    private var decided=false
    private var armed=false
    fun cancelBreak() { loader?.setPlayer(null); loader?.release(); loader=null; armed=false; decided=false; xml=null }
    fun beginBreak() {
        loader?.setPlayer(null); loader?.release(); loader=null
        adsId=Any(); xml=null; decided=false; armed=true
    }
    fun wrap(content: MediaSource,player: ExoPlayer,consentAllowed: Boolean): MediaSource {
        if(!armed) return content // Watch entry uses the separate fullscreen interstitial.
        // Freeze the decision at explicit playback start; no late consent-driven ad over content.
        if(!decided) {
            decided=true
            if(BuildConfig.VIEWER_TEST_ADS && consentAllowed) {
                xml=VideoAdSchedule.breakXml()
                loader=ImaAdsLoader.Builder(context).setVastLoadTimeoutMs(8_000)
                    .setMediaLoadTimeoutMs(8_000).setAdPreloadTimeoutMs(8_000)
                    .setPlayAdBeforeStartPosition(true).build().also { it.setPlayer(player) }
            }
        }
        val active=loader ?: return content
        val tag=Util.getDataUriForString("application/xml",checkNotNull(xml))
        return AdsMediaSource(content,DataSpec(tag),adsId,DefaultMediaSourceFactory(context),active,provider)
    }
    fun attach(parent: ViewGroup?) {
        if(parent==null || overlay.parent===parent) return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        parent.addView(overlay,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
    }
    fun detach(parent: ViewGroup?) { if(parent!=null && overlay.parent===parent) parent.removeView(overlay) }
    fun release() { loader?.setPlayer(null); loader?.release(); loader=null; (overlay.parent as? ViewGroup)?.removeView(overlay) }
}

