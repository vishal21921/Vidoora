package com.example.vidoora.feature.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.*

fun Context.adsActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.adsActivity()
    else -> null
}

@Composable
fun ViewerAdsSession(ads: ViewerAdsController?,eligible: Boolean,consentPage: Boolean=true) {
    val activity=LocalContext.current.adsActivity()
    if(ads==null) return
    val membership by ads.membershipState.collectAsState()
    if(membership.loading || membership.adFree || !ads.enabled || !eligible || activity==null) return
    val owner=remember(ads,activity) { Any() }
    DisposableEffect(ads,activity,owner,consentPage) {
        ads.attach(owner,activity,consentPage)
        onDispose { ads.detach(owner) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ads.prepare(activity) }
}

/** No overlays or fullscreen banners; SDK view and requests exist only while resumed. */
@Composable
fun ViewerBanner(ads: ViewerAdsController?,eligible: Boolean,horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    onLoadResult: (Boolean,Int?)->Unit={ _,_-> }) {
    val reportResult by rememberUpdatedState(onLoadResult)
    val activity=LocalContext.current.adsActivity()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    if(ads==null) return
    val membership by ads.membershipState.collectAsState()
    if(membership.loading || membership.adFree || !ads.enabled || !eligible || activity==null || lifecycleState!=Lifecycle.State.RESUMED) return
    val state by ads.state.collectAsState()
    if(!state.allowed || !state.initialized || state.privacyBusy) return
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=horizontalPadding,vertical=24.dp)) {
        val width=maxWidth.value.toInt()
        if(width<320) return@BoxWithConstraints
        val size=remember(activity,width) { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity,width) }
        var failed by remember(activity,width,state.generation) { mutableStateOf(false) }
        if(failed) return@BoxWithConstraints
        val view=remember(activity,width,state.generation) { AdView(activity) }
        DisposableEffect(view) {
            var active=true
            var requestId=ads.activityRecorder.request("BANNER")
            var loaded=false
            view.adUnitId=ViewerAdsController.BANNER_ID
            view.setAdSize(size)
            view.adListener=object: AdListener() {
                override fun onAdLoaded() {
                    if(loaded) requestId=ads.activityRecorder.request("BANNER") // SDK automatic refresh.
                    loaded=true
                    ads.activityRecorder.event(requestId,"BANNER","LOADED")
                    if(active) reportResult(true,null)
                }
                override fun onAdImpression() { ads.activityRecorder.event(requestId,"BANNER","IMPRESSION") }
                override fun onAdClicked() { ads.activityRecorder.event(requestId,"BANNER","CLICK") }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    if(loaded) { requestId=ads.activityRecorder.request("BANNER"); loaded=false }
                    ads.activityRecorder.event(requestId,"BANNER","FAILED")
                    if(active) { failed=true; reportResult(false,error.code) }
                }
            }
            try { view.loadAd(AdRequest.Builder().build()) } catch (_: Exception) {
                ads.activityRecorder.event(requestId,"BANNER","FAILED"); failed=true; reportResult(false,null)
            }
            onDispose { active=false; view.adListener=object: AdListener() {}; view.destroy() }
        }
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Advertisement",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            // Reserve the real ad size before loading, preventing a late ad from covering controls.
            AndroidView(factory={ view },modifier=Modifier.width(width.dp).height(size.height.dp))
        }
    }
}


