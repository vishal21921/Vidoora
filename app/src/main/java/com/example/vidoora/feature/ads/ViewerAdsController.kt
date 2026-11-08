package com.example.vidoora.feature.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.example.vidoora.BuildConfig
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.*
import com.google.android.ump.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

data class ViewerAdsState(val allowed: Boolean=false,val initialized: Boolean=false,val privacyRequired: Boolean=false,
    val privacyBusy: Boolean=false,val message: String?=null,val generation: Int=0,val watchBusy: Boolean=false)

/** Demo inventory only; there is deliberately no path accepting a live ad-unit ID. */
@Singleton
class ViewerAdsController @Inject constructor(@ApplicationContext private val context: Context,
    private val membership: com.example.vidoora.feature.membership.ViewerMembershipRepository,
    val activityRecorder: AdActivityRecorder) {
    companion object {
        private const val INTERSTITIAL_ID="ca-app-pub-3940256099942544/1033173712"
        const val BANNER_ID="ca-app-pub-3940256099942544/9214589741"
    }
    val enabled: Boolean get()=BuildConfig.VIEWER_TEST_ADS && membership.current().let { !it.loading && !it.adFree }
    val membershipState get()=membership.state
    private val mutable=MutableStateFlow(ViewerAdsState())
    val state=mutable.asStateFlow()
    private val main=Handler(Looper.getMainLooper())
    private var cached: InterstitialAd?=null
    private val requestIds=java.util.WeakHashMap<InterstitialAd,String>()
    private var loadedAt=0L
    private var loading=false
    private var waitingForAd: ((InterstitialAd?)->Unit)?=null
    private val watchGate=WatchAdGate<InterstitialAd>(scheduleTimeout={ timeout ->
        val action=Runnable { timeout() }; main.postDelayed(action,8_000)
        val cancel: ()->Unit = { main.removeCallbacks(action) }; cancel
    },busy={ value -> mutable.value=mutable.value.copy(watchBusy=value) })
    private val owners=mutableMapOf<Any,Boolean>()
    private var host=WeakReference<Activity>(null)
    private var consent: ConsentInformation?=null
    private var consentAttempted=false
    private var formPending=false
    private var consentFormsDeferred=false
    private var consentEpoch=0
    private var initializing=false
    private var sdkReady=false
    private var epoch=0
    fun membershipChanged() {
        if(!enabled) { clearAds(cancelWatch=false); deliverAd(null) }
        publishConsent()
    }
    fun attach(owner: Any,activity: Activity,consentPage: Boolean=true) {
        if(!enabled) return
        owners[owner]=consentPage
        if(consentPage) consentFormsDeferred=false
        prepare(activity)
    }
    fun detach(owner: Any) {
        owners.remove(owner)
        if(owners.isEmpty()) { clearAds() }
    }
    fun deferConsentForms() { consentFormsDeferred=true }
    private fun foreground(): Boolean {
        val activity=host.get() ?: return false
        return !activity.isFinishing && !activity.isDestroyed &&
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
    }
    private fun clearAds(cancelWatch: Boolean=true) {
        epoch++; cached=null; loading=false
        if(cancelWatch) this.cancelWatch()
        mutable.value=mutable.value.copy(generation=epoch)
    }
    private fun publishConsent(message: String?=null) {
        val info=consent
        val allowed=enabled && info?.canRequestAds()==true
        mutable.value=mutable.value.copy(allowed=allowed,initialized=sdkReady,privacyBusy=false,
            privacyRequired=info?.privacyOptionsRequirementStatus==ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
            message=message)
        if(!allowed) { clearAds(cancelWatch=false); deliverAd(null) }
        initializeIfAllowed()
    }
    fun prepare(activity: Activity) {
        if(!enabled || owners.isEmpty() || activity.isDestroyed || activity.isFinishing) return
        val old=host.get()
        if(old!==activity) {
            // Refresh UMP for each new app Activity launch, not merely once per surviving process.
            consentEpoch++; consentAttempted=false; formPending=false
            mutable.value=mutable.value.copy(allowed=false,privacyBusy=false)
            clearAds()
        }
        host=WeakReference(activity)
        if(!foreground()) return
        if(consentAttempted) {
            if(formPending && !consentFormsDeferred && owners.values.any { it } && !mutable.value.privacyBusy) {
                mutable.value=mutable.value.copy(privacyBusy=true)
                presentConsent(consentEpoch)
            } else initializeIfAllowed()
            return
        }
        consentAttempted=true
        val info=UserMessagingPlatform.getConsentInformation(context).also { consent=it }
        val version=++consentEpoch
        mutable.value=mutable.value.copy(privacyBusy=true)
        try {
            info.requestConsentInfoUpdate(activity,ConsentRequestParameters.Builder().build(),{
                if(version==consentEpoch) { formPending=true; presentConsent(version) }
            },{
                if(version==consentEpoch) publishConsent("Ad privacy setup is unavailable. Videos still work.")
            })
            // No guessed consent: UMP alone decides whether prior consent permits requests.
        } catch (_: Exception) { if(version==consentEpoch) publishConsent("Ad privacy setup is unavailable. Videos still work.") }
    }
    private fun presentConsent(version: Int) {
        val activity=host.get()
        // Never spring a delayed consent form over a video after the viewer has left details.
        if(activity==null || !foreground() || consentFormsDeferred || !owners.values.any { it }) { publishConsent(); return }
        try { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
            if(version==consentEpoch) {
                formPending=error!=null
                publishConsent(if(error==null) null else "Ad privacy setup is unavailable. Videos still work.")
            }
        } } catch (_: Exception) { publishConsent("Ad privacy setup is unavailable. Videos still work.") }
    }
    fun privacyOptions(activity: Activity) {
        if(!enabled || !mutable.value.privacyRequired || mutable.value.privacyBusy) return
        host=WeakReference(activity); clearAds()
        mutable.value=mutable.value.copy(allowed=false,privacyBusy=true)
        val version=++consentEpoch
        try { UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if(version==consentEpoch) publishConsent(if(error==null) null else "Could not open ad privacy choices. Please retry.")
        } } catch (_: Exception) { if(version==consentEpoch) publishConsent("Could not open ad privacy choices. Please retry.") }
    }
    private fun initializeIfAllowed() {
        if(!enabled || !mutable.value.allowed || mutable.value.privacyBusy || owners.isEmpty() || !foreground()) return
        if(sdkReady) { maybeLoadInterstitial(); return }
        if(initializing) return
        initializing=true
        // Initialization may perform work off the UI thread; all ad loads/shows remain on main.
        java.util.concurrent.Executors.newSingleThreadExecutor().also { executor ->
            executor.execute {
                try { MobileAds.initialize(context) {
                    main.post {
                        sdkReady=true; initializing=false
                        mutable.value=mutable.value.copy(initialized=true)
                        maybeLoadInterstitial()
                    }
                } } catch (_: Exception) { main.post { initializing=false; deliverAd(null) } }
                finally { executor.shutdown() }
            }
        }
    }
    fun cancelWatch() { waitingForAd=null; watchGate.cancel() }
    private fun deliverAd(ad: InterstitialAd?) {
        val callback=waitingForAd; waitingForAd=null
        callback?.invoke(ad)
    }
    private fun maybeLoadInterstitial() {
        if(mutable.value.privacyBusy) return
        if(!enabled || !mutable.value.allowed || !foreground() || owners.isEmpty()) { deliverAd(null); return }
        if(!sdkReady) { initializeIfAllowed(); return }
        if(waitingForAd==null && owners.values.none { it }) return // Never preload on the player/management shell.
        val ready=cached
        if(ready!=null && android.os.SystemClock.elapsedRealtime()-loadedAt<55*60_000L) { deliverAd(ready); return }
        cached=null
        if(loading) return
        loading=true
        val version=epoch
        val requestId=activityRecorder.request("INTERSTITIAL")
        try { InterstitialAd.load(context,INTERSTITIAL_ID,AdRequest.Builder().build(),object: InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                activityRecorder.event(requestId,"INTERSTITIAL","LOADED")
                if(version!=epoch) return
                requestIds[ad]=requestId
                loading=false; cached=ad; loadedAt=android.os.SystemClock.elapsedRealtime()
                deliverAd(ad)
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                activityRecorder.event(requestId,"INTERSTITIAL","FAILED")
                if(version!=epoch) return
                loading=false; cached=null; deliverAd(null)
            }
        }) } catch (_: Exception) { activityRecorder.event(requestId,"INTERSTITIAL","FAILED"); loading=false; deliverAd(null) }
    }
    /** Fresh access is checked by the caller and again by the player after dismissal. */
    fun watch(activity: Activity,valid: ()->Boolean,proceed: ()->Unit) {
        if(watchGate.pending) return
        host=WeakReference(activity)
        watchGate.open(enabled && foreground(),valid,load={ callback ->
            waitingForAd=callback
            maybeLoadInterstitial()
        },show={ ad,done ->
            cached=null
            if(!enabled || !foreground()) { done(); return@open }
            ad.fullScreenContentCallback=object: FullScreenContentCallback() {
                override fun onAdImpression() { requestIds[ad]?.let { activityRecorder.event(it,"INTERSTITIAL","IMPRESSION") } }
                override fun onAdClicked() { requestIds[ad]?.let { activityRecorder.event(it,"INTERSTITIAL","CLICK") } }
                override fun onAdDismissedFullScreenContent()=done()
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    requestIds[ad]?.let { activityRecorder.event(it,"INTERSTITIAL","SHOW_FAILED") }; done()
                }
            }
            ad.show(activity)
        },proceed=proceed)
    }
}

@HiltViewModel
class ViewerAdsViewModel @Inject constructor(val ads: ViewerAdsController): ViewModel()











