package com.example.vidoora

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vidoora.feature.playback.*
import com.example.vidoora.ui.theme.VidooraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic UI fixtures only. No auth, API, storage deletion or real user data. */
@RunWith(AndroidJUnit4::class)
class VideoDetailsUiTest {
    @get:Rule val compose=createComposeRule()
    private val id="11111111-1111-1111-1111-111111111111"
    private fun video(shared: Boolean=false,state: String="PROCESSED")=VideoDetailsDto(id,"Weekend trip",
        "A peaceful weekend with the family.",state,45,if(shared) "UNLISTED" else "PRIVATE",shared,true,state=="PROCESSED","Alex Morgan")
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val target=if(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap=target.captureToImage().asAndroidBitmap()
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ui-verification")
        dir.mkdirs()
        File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun scrollTo(text: String) { compose.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
    private fun showDetails(dto: VideoDetailsDto,onWatch: ()->Unit={},onEnable: ()->Unit={},onShare: ()->Unit={},onStop: ()->Unit={},viewerEntry: Boolean=true) {
        compose.setContent {
            VidooraTheme(darkTheme=false) {
                VideoDetailsScreen(state=VideoDetailsState(loading=false,video=dto),onRefresh={},onEnableSharing=onEnable,
                    onStopSharing=onStop,onShare=onShare,onWatch=onWatch,onBack={},onBlock={},onEdit={},onCancelEdit={},onSaveEdit={},
                    onEditTitle={},onEditDescription={},onDelete={},onMove={},onMoveSelect={},onMoveSave={},onMoveCancel={},viewerEntry=viewerEntry)
            }
        }
    }
    @Test fun privateDetailsShowsGroupedOwnerActionsAndEnablesOnlyOnTap() {
        var enables=0; var watches=0
        showDetails(video(),onWatch={ watches++ },onEnable={ enables++ })
        compose.onNodeWithText("Private video").assertExists()
        scrollTo("Edit details"); compose.onNodeWithText("Edit details").assertIsDisplayed()
        scrollTo("Move to folder"); compose.onNodeWithText("Move to folder").assertExists()
        compose.onNodeWithText("Share video").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        screenshot("details-private")
        assertEquals(0,enables)
        scrollTo("Watch video"); compose.onNodeWithText("Watch video").performClick()
        scrollTo("Enable link sharing"); compose.onNodeWithText("Enable link sharing").performClick()
        compose.runOnIdle { assertEquals(1,watches); assertEquals(1,enables) }
    }
    @Test fun uploaderOpeningLinkCanWatch() {
        var watches=0
        showDetails(video(shared=true),onWatch={ watches++ },viewerEntry=true)
        scrollTo("Watch video"); compose.onNodeWithText("Watch video").performClick()
        compose.runOnIdle { assertEquals(1,watches) }
    }
    @Test fun sharedDetailsPreservesShareAndStopActions() {
        var shares=0; var stops=0
        showDetails(video(shared=true),onShare={ shares++ },onStop={ stops++ })
        scrollTo("Watch video"); compose.onNodeWithText("Watch video").assertExists()
        scrollTo("Links expire after 30 days."); compose.onNodeWithText("Links expire after 30 days.").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        screenshot("details-shared")
        scrollTo("Share video"); compose.onNodeWithText("Share video").performClick()
        scrollTo("Stop sharing"); compose.onNodeWithText("Stop sharing").performClick()
        compose.runOnIdle { assertEquals(1,shares); assertEquals(1,stops) }
    }
    @Test fun processingCannotWatchOrShareButCanManageMetadata() {
        showDetails(video(state="PROCESSING"))
        compose.onNodeWithText("Processing").assertExists()
        compose.onNodeWithText("Watch video").assertDoesNotExist()
        compose.onNodeWithText("Enable link sharing").assertDoesNotExist()
        compose.onNodeWithText("Share video").assertDoesNotExist()
        scrollTo("Edit details"); compose.onNodeWithText("Edit details").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        screenshot("details-processing")
    }
    @Test fun staleSharingFlagCannotExposeShareActionsBeforeVideoIsPlayable() {
        showDetails(video(shared=true,state="PROCESSING"))
        compose.onNodeWithText("Edit details").let { scrollTo("Edit details"); it.assertIsDisplayed() }
        compose.onNodeWithText("Share video").assertDoesNotExist()
        compose.onNodeWithText("Stop sharing").assertDoesNotExist()
        compose.onNodeWithText("Enable link sharing").assertDoesNotExist()
        compose.onNodeWithText("Watch video").assertDoesNotExist()
    }
    @Test fun deleteConfirmationKeepsVideoUntilExplicitPermanentConfirmation() {
        var showing by mutableStateOf(true); var deletes=0
        compose.setContent { VidooraTheme(darkTheme=false) {
            if(showing) DeleteVideoConfirmation(onKeep={ showing=false },onDelete={ deletes++; showing=false })
        } }
        screenshot("delete-confirmation")
        compose.onNodeWithText("Keep video").performClick()
        compose.runOnIdle { assertEquals(0,deletes); showing=true }
        compose.onNodeWithText("Delete permanently").performClick()
        compose.runOnIdle { assertEquals(1,deletes) }
    }
    @Test fun blockedRowsRequireConfirmationBeforeUnblocking() {
        var unblocked: String?=null
        compose.setContent { VidooraTheme(darkTheme=false) {
            BlockedUploadersScreen(BlockedUploadersState(items=listOf(BlockedUploaderDto(id,"Jordan Davis"),BlockedUploaderDto("second","Samira Khan"))),
                onRefresh={},onMore={},onUnblock={ unblocked=it },onBack={})
        } }
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
        compose.onNodeWithText("Jordan Davis").assertIsDisplayed()
        screenshot("blocked-uploaders")
        compose.onAllNodesWithText("Unblock")[0].performClick()
        compose.runOnIdle { assertNull(unblocked) }
        compose.onNode(hasText("Unblock") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(id,unblocked) }
    }
    @Test fun unavailableOffersBackWithoutLoginOrCreatorActions() {
        var backs=0
        compose.setContent { VidooraTheme(darkTheme=false) { VideoUnavailableScreen({ backs++ }) } }
        compose.onNodeWithText("Video unavailable").assertIsDisplayed()
        compose.onNodeWithText("Log in").assertDoesNotExist()
        screenshot("video-unavailable")
        compose.onNodeWithText("Go back").performClick()
        compose.runOnIdle { assertEquals(1,backs) }
    }
}






