package com.example.vidoora

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vidoora.feature.ads.*
import com.example.vidoora.ui.theme.VidooraTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in Google demo-network smoke, never live inventory or an application/backend login. */
@RunWith(AndroidJUnit4::class)
class ViewerAdsSdkSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun consentOrNetworkUnavailabilityDoesNotBlockDemoPlayback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("adsSdkSmoke")=="true")
        val ads=freeAdsController(InstrumentationRegistry.getInstrumentation().targetContext)
        var continued=0
        val bannerResult=java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val bannerError=java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
        compose.setContent { VidooraTheme {
            Column {
                // Never force/accept consent in automation; defer any required form to a real details flow.
                ViewerAdsSession(ads,true,consentPage=false)
                Text("Synthetic viewer SDK check")
                Button(onClick={ continued++ }) { Text("Continue to video") }
                ViewerBanner(ads,true) { loaded,code -> bannerResult.set(loaded); bannerError.set(code ?: Int.MIN_VALUE) }
            }
        } }
        compose.onNodeWithText("Continue to video").performClick()
        compose.runOnIdle { assertEquals(1,continued) }
        val deadline=SystemClock.elapsedRealtime()+45_000
        while(ads.state.value.privacyBusy && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(250)
        if(ads.state.value.allowed) {
            val initDeadline=SystemClock.elapsedRealtime()+20_000
            while(!ads.state.value.initialized && SystemClock.elapsedRealtime()<initDeadline) SystemClock.sleep(250)
            val bannerDeadline=SystemClock.elapsedRealtime()+45_000
            while(bannerResult.get()==null && SystemClock.elapsedRealtime()<bannerDeadline) SystemClock.sleep(250)
            if(bannerResult.get()==true) SystemClock.sleep(1_000)
        }
        compose.onNodeWithText("Continue to video").performClick()
        compose.runOnIdle { assertEquals(2,continued) }
        val state=ads.state.value
        val outcome="allowed=${state.allowed}; initialized=${state.initialized}; privacyPending=${state.privacyBusy}; privacyMessage=${state.message!=null}; bannerLoaded=${bannerResult.get()}; bannerError=${bannerError.get()}"
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ads-sdk-smoke").apply { mkdirs() }
        File(folder,"outcome.txt").writeText(outcome)
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).let { bitmap ->
            File(folder,"screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply { putString("demo_ads_outcome",outcome) })
    }
}


