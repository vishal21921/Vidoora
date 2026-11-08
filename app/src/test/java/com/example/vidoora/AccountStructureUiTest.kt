package com.example.vidoora

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.MailTo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.vidoora.core.network.dto.UserDto
import com.example.vidoora.feature.ads.ViewerAdsController
import com.example.vidoora.feature.membership.*
import com.example.vidoora.feature.profile.*
import com.example.vidoora.feature.upload.CreatorHubScreen
import com.example.vidoora.ui.navigation.*
import com.example.vidoora.ui.theme.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountStructureUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val id="00000000-0000-0000-0000-000000000001"
    private fun snapshot(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val folder=File("build/reports/account-structure-ui").apply {mkdirs()}
        File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun tab(name: String)=compose.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab))
    @Test fun fourTabsNavigateAndRemainSelectableWithLargeText() {
        var route by mutableStateOf(NavRoutes.HOME)
        compose.setContent { VidooraTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                Scaffold(bottomBar={ReferenceBottomBar(route) {route=it}}) {padding->
                    Box(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {Text("Main tabs")}
                }
            }
        } }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).assertCountEquals(4)
        for((label,destination) in listOf("Home" to NavRoutes.HOME,"History" to NavRoutes.HISTORY,"Upload" to NavRoutes.UPLOAD,"Profile" to NavRoutes.PROFILE)) {
            tab(label).assertIsDisplayed().performClick().assertIsSelected()
            compose.runOnIdle {assertEquals(destination,route)}
        }
        snapshot("four-tabs-large-font")
    }
    @Test fun viewerBottomBarHidesUploadAndCreatorBottomBarKeepsIt() {
        var creator by mutableStateOf(false)
        compose.setContent {VidooraTheme {ReferenceBottomBar(NavRoutes.HOME,creator) {}}}
        compose.onNodeWithText("Upload").assertDoesNotExist()
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.runOnIdle {creator=true}
        compose.onNodeWithText("Upload").assertIsDisplayed()
    }
    @Test fun completedTransferShowsMeasuredProcessingUntilReadyToShare() {
        val task=com.example.vidoora.core.database.entity.UploadTaskEntity("task","video","session","",1000,"video/mp4","Video",null,"PRIVATE","UPLOADED",100,1)
        var status by mutableStateOf(com.example.vidoora.feature.upload.QueueProcessing("video","PROCESSING",42))
        compose.setContent {VidooraTheme {
            com.example.vidoora.feature.upload.UploadTaskCard(task,{},{},{},{},onViewVideo={},processing=status)
        }}
        compose.onNodeWithText("Processing 42%").assertIsDisplayed()
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithText("Progress").assertIsDisplayed()
        snapshot("queue-processing-42")
        compose.runOnIdle {status=status.copy(publicationState="PROCESSED",processingPercent=100)}
        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
    }
    @Test fun downloadAllowanceIsVisibleFromServerSnapshot() {
        compose.setContent {VidooraTheme {
            com.example.vidoora.feature.downloads.PrivateDownloadsScreen(emptyList(),"account",{},{},{},{},{},{},{},quota=com.example.vidoora.feature.downloads.DownloadQuotaDto(5,1,0,4))
        }}
        compose.onNodeWithText("4 of 5 downloads left").assertIsDisplayed()
    }
    @Test fun creatorToolsAreAllReachableFromUploadHub() {
        val calls=mutableListOf<String>()
        compose.setContent { VidooraTheme {
            Scaffold(bottomBar={ReferenceBottomBar(NavRoutes.UPLOAD) {}}) {padding -> Box(Modifier.padding(padding)) {
                CreatorHubScreen(true,{}, {calls+="new"},{calls+="videos"},{calls+="queue"},{calls+="folders"},{calls+="blocked"})
            } }
        } }
        snapshot("creator-hub")
        for(label in listOf("Upload a video","My Videos","Upload queue","Folders","Blocked uploaders"))
            compose.onNodeWithText(label).performScrollTo().performClick()
        compose.runOnIdle {assertEquals(listOf("new","videos","queue","folders","blocked"),calls)}
    }
    @Test fun guestsSeeCreatorLoginInsteadOfOtherAccountsTools() {
        var logins=0
        compose.setContent {VidooraTheme {CreatorHubScreen(false,{logins++},{fail()},{fail()},{fail()},{fail()},{fail()})}}
        compose.onNodeWithText("My Videos").assertIsNotEnabled()
        compose.onNodeWithText("Upload a video").assertDoesNotExist()
        compose.onNodeWithText("Log in / Create account").performClick()
        compose.runOnIdle {assertEquals(1,logins)}
    }
    @Test fun profileShowsRealIdPlanAndAccountActionsWhileCreatorToolsAreMovedOut() {
        val calls=mutableListOf<String>()
        var member by mutableStateOf(MembershipView(loading=false))
        compose.setContent { VidooraTheme {
            Scaffold(bottomBar={ReferenceBottomBar(NavRoutes.PROFILE) {}}) {padding ->Box(Modifier.padding(padding)) {
                ProfileScreen(UserDto(id,"demo","demo@example.test","Demo Creator",createdAt="2026-10-30T00:00:00Z"),{},{calls+="logout"},
                    onPlans={calls+="plans"},membership=member,onPayments={calls+="payments"},onDownloads={calls+="downloads"},
                    onSupport={calls+="support"},onSettings={calls+="settings"})
            } }
        } }
        compose.onNodeWithText(id).assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle {
            val clipboard=compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(id,clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        snapshot("profile-account-top")
        compose.onNodeWithText("My Videos").assertDoesNotExist();compose.onNodeWithText("Blocked uploaders").assertExists()
        compose.onNodeWithTag("membership_entry").performScrollTo().performClick()
        for(label in listOf("Payments & transactions","Downloads","Support centre","Settings"))
            compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithText("Share app").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Rate Vidoora").performScrollTo().assertIsNotEnabled()
        snapshot("profile-account-actions")
        compose.runOnIdle {
            assertEquals(listOf("plans","payments","downloads","support","settings"),calls)
            member=MembershipView("MONTHLY",true,null,"2026-11-30T12:00:00Z",false)
        }
        compose.onNodeWithTag("membership_entry").performScrollTo()
        compose.onNodeWithText("Monthly Ad-Free").assertExists()
        compose.onNodeWithText("Valid until",substring=true).assertExists()
    }
    @Test fun supportBuildsAUserReviewedDraftWithTheCorrectRecipientAndReferences() {
        var draft: Intent?=null
        compose.setContent {VidooraTheme {SupportCentreScreen(id,{}, onEmail={topic,reference,message->draft=supportEmailIntent(id,topic,reference,message)})}}
        compose.onNodeWithText("Open email app").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Transaction ID (optional)").performScrollTo().performTextInput("TX-fixture-42")
        compose.onNodeWithText("How can we help?").performScrollTo().performTextInput("Please check my plan. &bcc=someone@example.test")
        compose.onNodeWithText("Open email app").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_SENDTO,draft!!.action)
            val mail=MailTo.parse(draft!!.data.toString())
            assertEquals(VIDOORA_SUPPORT_EMAIL,mail.to)
            val body=draft!!.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            assertTrue(body.contains(id));assertTrue(body.contains("TX-fixture-42"))
            assertTrue(body.contains("&bcc=someone@example.test"));assertNull(mail.headers["bcc"])
            assertFalse(draft!!.data.toString().contains("?"));assertNull(draft!!.getStringArrayExtra(Intent.EXTRA_BCC))
        }
        snapshot("support-centre")
    }
    @Test fun settingsKeepsPlanEntryAndDoesNotInitializeAds() {
        val client=HttpClient(MockEngine {throw AssertionError("Settings must not start requests")})
        var plans=0
        try {
            val repository=ViewerMembershipRepository(client,{"guest"},{0})
            val ads=ViewerAdsController(compose.activity,repository,com.example.vidoora.feature.ads.AdActivityRecorder(client,
                com.example.vidoora.core.security.SecureStorageManager(compose.activity)))
            compose.setContent {VidooraTheme {AccountSettingsScreen(MembershipView(loading=false),ads,{}, {plans++})}}
            compose.onNodeWithTag("membership_entry").performClick()
            compose.onNodeWithText("Ad privacy choices").assertIsNotEnabled()
            compose.runOnIdle {assertEquals(1,plans);assertFalse(ads.state.value.initialized)}
        } finally {client.close()}
    }
}
