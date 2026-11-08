package com.example.vidoora.feature.membership

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vidoora.feature.ads.ViewerAdsController
import com.example.vidoora.feature.ads.VideoWatchAdClock
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ViewerMembershipAdsTest {
    @Test fun allAdSurfacesUseSameVerifiedGateAndAccountChangeClearsItImmediately()=runBlocking {
        val account="00000000-0000-0000-0000-000000000001"
        var identity="user:$account";var paid=true;var elapsed=100L
        val client=HttpClient(MockEngine {
            val body=if(paid) """{"success":true,"data":{"accountId":"$account","planId":"MONTHLY","adFree":true,"maxHeight":null,"expiresAt":"2026-11-30T00:00:00Z","validForSeconds":60,"downloadsPerPeriod":30}}"""
                else """{"success":true,"data":{"validForSeconds":30}}"""
            respond(body,headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) {
            defaultRequest { url("https://fixture.test/") };install(io.ktor.client.plugins.HttpTimeout)
            install(ContentNegotiation) { json() }
        }
        try {
            val membership=ViewerMembershipRepository(client,{ identity },{ elapsed })
            val context=ApplicationProvider.getApplicationContext<Context>()
            val ads=ViewerAdsController(context,membership,com.example.vidoora.feature.ads.AdActivityRecorder(client,
                com.example.vidoora.core.security.SecureStorageManager(context)))
            assertFalse(ads.enabled) // Do not initialize/show ads while initial verification is pending.
            membership.refresh();assertTrue(membership.current().adFree);assertFalse(ads.enabled)
            identity="guest";assertFalse(membership.current().adFree);assertFalse(ads.enabled)
            paid=false;membership.refresh();assertTrue(ads.enabled)
        } finally { client.close() }
    }
    @Test fun paidTimeDoesNotLeaveAnImmediateTenMinuteBreakWaitingAfterExpiry() {
        val clock=VideoWatchAdClock()
        clock.update(0,true);clock.update(VideoWatchAdClock.INTERVAL_MS,true)
        clock.reset(VideoWatchAdClock.INTERVAL_MS)
        assertEquals(0L,clock.watchedMs);assertFalse(clock.takeBreak(1_000_000))
        clock.update(2*VideoWatchAdClock.INTERVAL_MS,false)
        assertEquals(0L,clock.watchedMs)
    }
}
