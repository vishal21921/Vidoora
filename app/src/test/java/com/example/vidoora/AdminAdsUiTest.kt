package com.example.vidoora

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.vidoora.feature.analytics.*
import com.example.vidoora.ui.theme.VidooraTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminAdsUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun client(data: String)=HttpClient(MockEngine { respond("""{"success":true,"data":$data}""",headers=headersOf(HttpHeaders.ContentType,"application/json")) }) {
        defaultRequest {url("https://fixture.test/")};install(ContentNegotiation) {json(Json {ignoreUnknownKeys=true})}
    }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val dir=File("build/reports/admin-ads-ui").apply {mkdirs()}
            File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun adReportSeparatesRequestsFromImpressionsAndDoesNotInventEarnings() {
        val client=client("""{"from":"2026-11-07","to":"2026-11-07","updatedAt":"2026-11-07T12:00:00Z","formats":[{"format":"INTERSTITIAL","requests":10,"loaded":8,"impressions":3,"clicks":0,"failures":2}],"devices":2,"trend":[],"recent":[],"testOnly":true,"source":"fixture"}""")
        try {
            compose.setContent {VidooraTheme {AdAnalyticsScreen(CreatorAnalyticsViewModel(client),{})}}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Total Ad Impressions").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Impressions: 3 | Requests: 10").assertExists()
            compose.onNodeWithText("Ad Requests").assertExists()
            compose.onNodeWithText("Estimated Earnings").assertDoesNotExist()
            compose.onNodeWithText("This Month's Earnings").assertDoesNotExist()
            compose.onNodeWithText("ADMIN · TEST ADS").assertExists()
            snapshot("ads-analytics-fixture")
        } finally {client.close()}
    }
    @Test fun paymentStatusIsACompactBadgeWithoutStatusPrefix() {
        val client=client("""{"day":1,"week":0,"month":0,"items":[{"reference":"fixture-txn","userId":"10000007","plan":"DAY","status":"SUCCESSFUL","activation":"ACTIVE","time":"2026-11-07T12:00:00Z","expiry":"2026-11-08T12:00:00Z","updatedAt":"2026-11-07T12:00:00Z"}],"hasNext":false}""")
        try {
            compose.setContent {VidooraTheme {AdminPaymentsScreen(CreatorAnalyticsViewModel(client),{})}}
            compose.waitUntil(10000) {compose.onAllNodesWithText("TXN ID: fixture-txn").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Status:").assertDoesNotExist()
            compose.onNodeWithText("This Month's Earnings").assertExists()
            compose.onNodeWithText("15th–14th").assertDoesNotExist()
            compose.onNodeWithText("30 Days").assertExists()
            snapshot("admin-payments-fixture")
        } finally {client.close()}
    }
    @Test fun adminToolbarReservesStatusBarSpaceAndKeepsStandardHeight() {
        compose.setContent {VidooraTheme {
            Box(Modifier.testTag("admin_header")) {
                AdminCompactHeader("Subscriptions & Payments",{}, {}, insets=androidx.compose.foundation.layout.WindowInsets(top=24))
            }
        }}
        val title=compose.onNodeWithText("Subscriptions & Payments").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Title must sit below the status bar",title.top>=24f)
        val header=compose.onNodeWithTag("admin_header").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Toolbar keeps 64px content plus the 24px status inset",header.height>=88f)
        snapshot("admin-toolbar-status-inset")
    }
    @Test fun creatorCodeScreenOnlyShowsNecessaryControlsWithoutStartingRequests() {
        val client=HttpClient(MockEngine {throw AssertionError("No invite should be generated automatically")})
        try {
            compose.setContent {VidooraTheme {CreatorCodeScreen(CreatorAnalyticsViewModel(client),{})}}
            compose.onNodeWithText("Generate code").assertExists()
            compose.onNodeWithText("Give upload access to one member. Each code can be used once and expires after five minutes.").assertDoesNotExist()
            snapshot("creator-code-minimal")
        } finally {client.close()}
    }
}
