package com.example.vidoora

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vidoora.core.database.entity.UploadTaskEntity
import com.example.vidoora.feature.upload.UploadQueueScreen
import com.example.vidoora.ui.theme.VidooraTheme
import org.junit.Assert.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UploadQueueUiTest {
    @get:Rule val compose=createComposeRule()
    private fun task(status: String="PAUSED")=UploadTaskEntity("fixture","fixture-video","fixture-session","",42_000_000,"video/mp4","Weekend trip",null,"PRIVATE",status,64,8,5,userId="fixture-owner")
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val colors=mutableSetOf<Int>()
        for(y in 0 until bitmap.height step 20) for(x in 0 until bitmap.width step 20) colors+=bitmap.getPixel(x,y)
        assertTrue("Nonblank capture",colors.size>4)
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"queue-redesign").apply { mkdirs() }
        File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun swipeRevealsDeleteAndConfirmationControlsPermanentAction() {
        var deleted=0;var cancelled=0
        compose.setContent { VidooraTheme {
            UploadQueueScreen(listOf(task()),{},{},{},{_,_,_->cancelled++},{},{},onDeleteClick={ assertEquals("fixture",it); deleted++ })
        } }
        compose.onNodeWithText("Weekend trip").assertIsDisplayed()
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithContentDescription("Upload options").performClick()
        compose.onNodeWithText("Cancel upload").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,cancelled) }
        compose.onNodeWithText("40.1 MB").assertDoesNotExist()
        screenshot("queue-paused")
        compose.onNodeWithText("Weekend trip").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Delete").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete video?").assertIsDisplayed()
        assertEquals(0,deleted)
        compose.onNodeWithText("Keep video").performClick();assertEquals(0,deleted)
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1,deleted) }
    }
    @Test fun completedUploadHidesProgressAndOpensProcessingSharing() {
        var opened=0
        compose.setContent { VidooraTheme {
            UploadQueueScreen(listOf(task("UPLOADED")),{},{},{},{_,_,_->},{},{},onOpenVideo={opened++},onDeleteClick={})
        } }
        compose.onNodeWithText("Uploaded").assertIsDisplayed()
        compose.onNodeWithText("64%").assertDoesNotExist()
        compose.onNodeWithText("Share").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,opened) }
        screenshot("queue-completed")
    }
    @Test fun splashWaitsTwoSecondsAndStillWaitsForSessionRestoration() {
        compose.mainClock.autoAdvance=false
        var navigated=0
        var state by androidx.compose.runtime.mutableStateOf<com.example.vidoora.feature.splash.SplashState>(com.example.vidoora.feature.splash.SplashState.Initial)
        compose.setContent { VidooraTheme { com.example.vidoora.feature.splash.SplashScreen(state,{navigated++},{navigated++}) } }
        compose.mainClock.advanceTimeBy(1800);compose.runOnIdle { assertEquals(0,navigated) }
        compose.onNodeWithText("Vidoora").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();action(results)
            assertEquals(com.example.vidoora.ui.theme.VidooraColors.Text,results.single().layoutInput.style.color)
        }
        screenshot("splash")
        compose.mainClock.advanceTimeBy(300);compose.runOnIdle { assertEquals(0,navigated);state=com.example.vidoora.feature.splash.SplashState.Unauthenticated }
        compose.mainClock.advanceTimeByFrame();compose.runOnIdle { assertEquals(1,navigated) }
    }
    @Test fun libraryShowsSingleLineTitleAndDurationOnThumbnail() {
        val client=io.ktor.client.HttpClient()
        val title="A very long weekend video title that must stay on one line"
        try {
            compose.setContent { VidooraTheme {
                com.example.vidoora.feature.playback.VideoLibraryScreen(
                    state=com.example.vidoora.feature.playback.VideoLibraryState(loading=false,videos=listOf(
                        com.example.vidoora.feature.playback.LibraryVideoDto("fixture",title,"PROCESSED",487,"UNLISTED",published=true))),
                    onRefresh={},onPlay={},onBack={},onSearchText={},onSearch={},onMore={},
                    thumbnailLoader=com.example.vidoora.feature.playback.PrivateThumbnailLoader(client),
                    onSort={},onStatus={},onSharing={},onReset={},folders=com.example.vidoora.feature.playback.FoldersState(loading=false,loaded=true),onFolder={},onManageFolders={})
            } }
            compose.onNodeWithText("8:07").assertIsDisplayed()
            compose.onNodeWithText("Shared by link").assertIsDisplayed()
            compose.onNodeWithText(title).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
                val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();action(results);assertEquals(1,results.single().lineCount)
            }
            screenshot("library-compact")
        } finally { client.close() }
    }
    @Test fun readySessionStillShowsSplashForTwoSeconds() {
        compose.mainClock.autoAdvance=false
        var navigated=0
        compose.setContent { VidooraTheme { com.example.vidoora.feature.splash.SplashScreen(
            com.example.vidoora.feature.splash.SplashState.Authenticated,{navigated++},{navigated++}) } }
        compose.mainClock.advanceTimeBy(1800);compose.runOnIdle { assertEquals(0,navigated) }
        compose.mainClock.advanceTimeBy(400);compose.runOnIdle { assertEquals(1,navigated) }
    }
}
