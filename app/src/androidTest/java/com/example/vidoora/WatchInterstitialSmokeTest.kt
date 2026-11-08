package com.example.vidoora

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vidoora.feature.ads.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in Google demo interstitial: no private media, credentials or creative clicks. */
@RunWith(AndroidJUnit4::class)
class WatchInterstitialSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun watchShowsFullscreenDemoOrContinuesOnUnavailableInventory() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("watchAdSmoke")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation()
        val ads=freeAdsController(instrument.targetContext)
        val forwards=AtomicInteger(0)
        compose.setContent {
            ViewerAdsSession(ads,true)
            Column { Button(onClick={
                ads.deferConsentForms()
                ads.watch(compose.activity,{!compose.activity.isFinishing},{forwards.incrementAndGet()})
            }) { Text("Watch demo") } }
        }
        compose.waitForIdle()
        val privacyDeadline=SystemClock.elapsedRealtime()+45_000
        while(ads.state.value.privacyBusy && SystemClock.elapsedRealtime()<privacyDeadline) SystemClock.sleep(250)
        assumeTrue("No consent permission; never force consent",ads.state.value.allowed)
        val initDeadline=SystemClock.elapsedRealtime()+20_000
        while(!ads.state.value.initialized && SystemClock.elapsedRealtime()<initDeadline) SystemClock.sleep(250)
        val monitor=instrument.addMonitor("com.google.android.gms.ads.AdActivity",null,false)
        try {
            compose.onNodeWithText("Watch demo").performClick()
            val fullscreen=instrument.waitForMonitorWithTimeout(monitor,15_000)
            if(fullscreen!=null) {
                // Back is a close attempt, never an ad-creative click; SDK controls when closing is permitted.
                SystemClock.sleep(15_000)
                instrument.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            val deadline=SystemClock.elapsedRealtime()+40_000
            while(forwards.get()==0 && SystemClock.elapsedRealtime()<deadline) {
                SystemClock.sleep(500)
                if(fullscreen!=null && !fullscreen.isFinishing && SystemClock.elapsedRealtime()>deadline-15_000) {
                    instrument.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }
            val folder=File(instrument.targetContext.getExternalFilesDir(null),"watch-interstitial-smoke").apply { mkdirs() }
            File(folder,"outcome.txt").writeText("consentAllowed=true; fullscreenActivityObserved=${fullscreen!=null}; continuations=${forwards.get()}")
            assertEquals("One playback continuation after dismissal/failure/timeout",1,forwards.get())
        } finally { instrument.removeMonitor(monitor); compose.runOnIdle { ads.cancelWatch() } }
    }
}
