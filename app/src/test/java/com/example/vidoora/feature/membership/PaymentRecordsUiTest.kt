package com.example.vidoora.feature.membership

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.vidoora.ui.theme.VidooraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentRecordsUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val ref="00000000-0000-0000-0000-000000000123"
    @Test fun pendingRecordProvidesMatchingSupportAndRetryReferencesAtLargeFont() {
        var help="";var retry=""
        compose.setContent {VidooraTheme {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,1.5f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    PaymentRecordsContent(PaymentsState(loading=false,available=true,items=listOf(PaymentItem(ref,"PENDING","PENDING",createdAt="2026-10-30T00:00:00Z",updatedAt="2026-10-30T00:00:00Z",retryable=true))),{},{},{retry=it},{help=it})
                }
            }
        }}
        compose.onNodeWithText("Payment pending").assertExists()
        compose.onNodeWithText("Get help").performScrollTo().performClick()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(ref,help);assertEquals(ref,retry)}
    }
    @Test fun unavailableStateDoesNotInventPaymentOrActivation() {
        compose.setContent {VidooraTheme {Column {PaymentRecordsContent(PaymentsState(loading=false),{},{},{},{})}}}
        compose.onNodeWithText("Purchases remain unavailable.",substring=true).assertExists()
        compose.onNodeWithText("Payment verified").assertDoesNotExist()
        compose.onNodeWithText("Activation verified").assertDoesNotExist()
    }
}
