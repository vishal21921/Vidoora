package com.example.vidoora

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vidoora.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic design-system fixture only: no app navigation, login, SDK ad requests or user data. */
@RunWith(AndroidJUnit4::class)
class CinematicDesignSystemTest {
    @get:Rule val compose=createComposeRule()
    private fun screenshot(name: String) {
        var last: AssertionError?=null
        repeat(3) { attempt ->
            try {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
                val samples=mutableSetOf<Int>()
                for(y in 0 until bitmap.height step maxOf(1,bitmap.height/20)) {
                    for(x in 0 until bitmap.width step maxOf(1,bitmap.width/20)) samples+=bitmap.getPixel(x,y)
                }
                assertTrue("Screenshot must contain rendered UI, not a blank frame",samples.size>4)
                val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"cinematic-stage1").apply { mkdirs() }
                File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                return
            } catch(error: AssertionError) {
                last=error
                if(attempt<2) android.os.SystemClock.sleep(750)
            }
        }
        throw checkNotNull(last)
    }
    @Test fun sharedActionsKeepCallbacksBusyStateAndEditableFieldSemantics() {
        var clicks=0; var title by mutableStateOf("")
        compose.setContent { VidooraTheme {
            Column(Modifier.padding(20.dp)) {
                PrimaryAction("Watch video",{clicks++})
                VidooraGradientButton("Saving",{clicks++},loading=true)
                VidooraOutlinedButton("Disabled action",{clicks++},enabled=false)
                VidooraTextField(title,{title=it},"Video title")
            }
        } }
        compose.onNodeWithText("Watch video").performClick()
        compose.onNodeWithText("Saving").assertIsNotEnabled()
        compose.onNodeWithText("Disabled action").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("My clip")
        compose.runOnIdle { assertEquals(1,clicks); assertEquals("My clip",title) }
        val bounds=compose.onNodeWithText("Watch video").getUnclippedBoundsInRoot()
        assertTrue((bounds.bottom-bounds.top)>=48.dp)
    }
    @Test fun darkGalleryRendersAndLowerStatesRemainReachable() {
        compose.setContent { VidooraTheme { VidooraDesignSystemGallery() } }
        compose.onNodeWithText("Vidoora design system").assertIsDisplayed()
        compose.onNodeWithText("Primary action").assertIsDisplayed()
        screenshot("dark-top")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Disabled action"))
        compose.onNodeWithText("Disabled action").assertIsNotEnabled()
        screenshot("dark-states")
    }
    @Test fun narrowLargeTextGalleryWrapsAndKeepsActionsInsideItsBounds() {
        compose.setContent { VidooraTheme {
            val density=LocalDensity.current.density
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.TopCenter) {
                Box(Modifier.width(320.dp).fillMaxHeight().testTag("narrow-frame")) {
                    CompositionLocalProvider(LocalDensity provides Density(density,2f)) { VidooraDesignSystemGallery() }
                }
            }
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Primary action"))
        compose.onNodeWithText("Primary action").assertIsDisplayed()
        val frame=compose.onNodeWithTag("narrow-frame").getUnclippedBoundsInRoot()
        val action=compose.onNodeWithText("Primary action").getUnclippedBoundsInRoot()
        assertTrue(action.left>=frame.left && action.right<=frame.right && (action.bottom-action.top)>=48.dp)
        screenshot("narrow-large-text")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("A clear, readable validation message."))
        compose.onNodeWithText("A clear, readable validation message.").assertIsDisplayed()
        screenshot("narrow-large-fields")
    }
}



