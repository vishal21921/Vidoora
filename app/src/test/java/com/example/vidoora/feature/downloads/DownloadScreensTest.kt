package com.example.vidoora.feature.downloads

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.vidoora.ui.theme.VidooraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadScreensTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val account=UUID.randomUUID().toString()
    private fun item(owner: String=account): OfflineItem {
        val video=UUID.randomUUID().toString()
        return OfflineItem(UUID.randomUUID().toString(),owner,video,manifest=OfflineManifest(UUID.randomUUID().toString(),video,"Private fixture",6,"version","480p/index.m3u8",listOf("480p/index.m3u8")),state="COMPLETE")
    }
    @Test fun largeFontDownloadActionsWorkAndRemovalRequiresConfirmation() {
        val item=item();var played="";var removed=""
        compose.setContent {VidooraTheme {val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,1.5f)) {
                PrivateDownloadsScreen(listOf(item),account,{},{},{},{played=it.id},{},{},{removed=it.id})
            }
        }}
        compose.onNodeWithText("Watch offline").performScrollTo().performClick()
        compose.runOnIdle{assertEquals(item.id,played)}
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.onNodeWithText("Remove download?").assertExists()
        compose.onNodeWithText("Keep").performClick();compose.runOnIdle{assertEquals("",removed)}
    }
    @Test fun guestCannotSeeAnotherAccountsDownloads() {
        compose.setContent {VidooraTheme {PrivateDownloadsScreen(listOf(item()),null,{},{},{},{},{},{},{})}}
        compose.onNodeWithText("Log in for downloads").assertExists()
        compose.onNodeWithText("Private fixture").assertDoesNotExist()
    }
}
