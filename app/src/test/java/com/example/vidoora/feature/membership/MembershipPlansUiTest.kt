package com.example.vidoora.feature.membership

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.example.vidoora.feature.home.HomeScreen
import com.example.vidoora.feature.profile.ProfileScreen
import com.example.vidoora.ui.theme.VidooraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** UI fixtures only: no purchase client, API, credentials, ad requests or real user records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MembershipPlansUiTest {
    @Test fun configuredCheckoutUsesStorePriceAndOnlySelectedOfferCallback() {
        var chosen=""
        compose.setContent { VidooraTheme { MembershipPlansScreen(MembershipView(loading=false),{},{},
            checkout=PlanCheckoutUi(mapOf("DAY" to "₹13.00"),enabled=true),onPurchase={chosen=it}) } }
        compose.onNodeWithText("₹13.00").assertExists()
        compose.onNodeWithTag("purchase_DAY").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals("DAY",chosen)}
        compose.onNodeWithTag("purchase_WEEK").performScrollTo().assertIsNotEnabled()
    }
    @Test fun activeMembershipAndServerDisabledCheckoutCannotPurchase() {
        var enabled by mutableStateOf(false)
        var active by mutableStateOf(false)
        compose.setContent { VidooraTheme { MembershipPlansScreen(
            MembershipView(planId=if(active) "DAY" else "FREE",adFree=active,loading=false),{},{},
            checkout=PlanCheckoutUi(mapOf("DAY" to "₹13.00"),enabled=enabled),onPurchase={fail("Must not purchase")}) } }
        compose.onNodeWithTag("purchase_DAY").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {active=true;enabled=true}
        compose.onNodeWithTag("purchase_DAY").assertIsNotEnabled()
    }
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
            image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
        }
        val colors=mutableSetOf<Int>()
        for(y in 0 until image.height step maxOf(1,image.height/30))
            for(x in 0 until image.width step maxOf(1,image.width/20)) colors+=image.getPixel(x,y)
        assertTrue("Expected a rendered screen",colors.size>4)
        val folder=File("build/reports/membership-ui").apply { mkdirs() }
        File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun plansAreReadableScrollableAndCannotStartPurchases() {
        var backs=0;var refreshes=0
        compose.setContent { VidooraTheme { MembershipPlansScreen(MembershipView(loading=false),{backs++},{refreshes++}) } }
        compose.onNodeWithText("Plans preview").assertIsDisplayed()
        compose.onNodeWithText("Payments and downloads aren’t available yet.").assertIsDisplayed()
        screenshot("plans-top")
        for((id,price) in listOf("DAY" to "₹13","WEEK" to "₹39","MONTHLY" to "₹99")) {
            compose.onNodeWithTag("purchase_$id").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(price).assertExists()
        }
        compose.onNodeWithText("30 downloads / billing period").assertExists()
        compose.onNodeWithContentDescription("Refresh access").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1,backs);assertEquals(1,refreshes) }
    }
    @Test fun largeFontMonthlyLabelsWrapWithoutTruncation() {
        compose.setContent { VidooraTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                MembershipPlansScreen(MembershipView(loading=false),{},{})
            }
        } }
        compose.onNodeWithTag("purchase_MONTHLY").performScrollTo().assertIsNotEnabled()
        for(label in listOf("Monthly Ad-Free","30 downloads / billing period","₹99")) {
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            for(layout in layouts) {
                assertFalse(label,layout.didOverflowHeight)
                for(line in 0 until layout.lineCount) {
                    // Intrinsic text widths can round down by a fraction of one physical pixel.
                    assertTrue(label,layout.getLineRight(line)-layout.getLineLeft(line)<=layout.size.width+1f)
                    assertFalse(label,layout.isLineEllipsized(line))
                }
            }
        }
        screenshot("plans-large-font")
    }
    @Test fun homeAndGuestProfileExposeTheSamePlansCallback() {
        var profile by mutableStateOf(false);var opens=0
        compose.setContent { VidooraTheme {
            if(profile) ProfileScreen(null,{},{},onPlans={opens++})
            else HomeScreen(onPlans={opens++},onOpenLink={})
        } }
        compose.onNodeWithTag("membership_entry").performScrollTo().performClick()
        screenshot("home-plans-entry")
        compose.runOnIdle { assertEquals(1,opens);profile=true }
        compose.onNodeWithTag("membership_entry").performScrollTo().performClick()
        screenshot("profile-plans-entry")
        compose.runOnIdle { assertEquals(2,opens) }
    }
    @Test fun loadingNeverPretendsAPlanIsActive() {
        compose.setContent { VidooraTheme { MembershipPlansScreen(MembershipView(),{},{}) } }
        compose.onNodeWithText("Checking access…").assertIsDisplayed()
        compose.onNodeWithText("Free viewing").assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh access").assertIsNotEnabled()
        compose.onNodeWithTag("purchase_DAY").performScrollTo().assertIsNotEnabled()
    }
}
