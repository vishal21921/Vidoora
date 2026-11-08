package com.example.vidoora

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.example.vidoora.core.network.SharedVideoLink
import com.example.vidoora.core.network.VideoShareAccess
import com.example.vidoora.feature.playback.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerWatchAccessTest {
    private val id="11111111-1111-1111-1111-111111111111"
    private val headers=headersOf(HttpHeaders.ContentType,"application/json")
    private fun json(owner: Boolean=false,playable: Boolean=true)="""{"success":true,"data":{"id":"$id","title":"Fixture","publicationState":"PROCESSED","durationSeconds":30,"visibility":"UNLISTED","published":true,"isOwner":$owner,"canPlay":$playable,"creatorName":"Fixture"}}"""
    @Test fun watchRechecksCapabilityAndDeniedVideosNeverReachAdOrNavigationCallback()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var status=HttpStatusCode.OK; var calls=0; var ready=0
        val access=VideoShareAccess().apply { remember(SharedVideoLink(id,"fixture.capability.token")) }
        val client=HttpClient(MockEngine { request ->
            calls++; assertEquals("fixture.capability.token",request.headers["X-Vidoora-Share"])
            assertTrue(request.url.parameters.isEmpty()); respond(if(status==HttpStatusCode.OK) json() else "{}",status,headers)
        }) { install(ContentNegotiation) { json() } }
        try {
            val vm=VideoDetailsViewModel(client,access,SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }; assertEquals(1,calls)
            vm.watch { ready++ }; vm.state.first { !it.loading }; assertEquals(1,ready); assertEquals(2,calls)
            status=HttpStatusCode.Forbidden; vm.watch { ready++ }; vm.state.first { !it.loading }
            assertEquals(1,ready); assertNull(vm.state.value.video); assertEquals(3,calls)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun freshOwnerAndTransientFailureArePreservedInsteadOfForcingViewerAds()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var owner=false; var status=HttpStatusCode.OK; var readyOwner: Boolean?=null
        val client=HttpClient(MockEngine { respond(json(owner),status,headers) }) { install(ContentNegotiation) { json() } }
        try {
            val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }; owner=true
            vm.watch { readyOwner=it.isOwner }; vm.state.first { !it.loading }; assertEquals(true,readyOwner)
            readyOwner=null; status=HttpStatusCode.ServiceUnavailable
            vm.watch { readyOwner=it.isOwner }; vm.state.first { !it.loading }
            assertNull(readyOwner); assertNotNull(vm.state.value.video); assertNotNull(vm.state.value.error)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun duplicateWatchAndChangedCapabilityCannotForwardStaleResponse()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var calls=0; var ready=0; val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val access=VideoShareAccess().apply { remember(SharedVideoLink(id,"first.fixture.token")) }
        val client=HttpClient(MockEngine {
            calls++; if(calls>1) { entered.complete(Unit); release.await() }; respond(json(),headers=headers)
        }) { install(ContentNegotiation) { json() } }
        try {
            val vm=VideoDetailsViewModel(client,access,SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }; vm.watch { ready++ }; entered.await()
            vm.watch { ready++ }; assertEquals(2,calls)
            access.remember(SharedVideoLink(id,"second.fixture.token")); vm.ensureCurrentAccess()
            release.complete(Unit); vm.state.first { !it.loading }; assertEquals(0,ready)
        } finally { release.complete(Unit); client.close(); Dispatchers.resetMain() }
    }
}
