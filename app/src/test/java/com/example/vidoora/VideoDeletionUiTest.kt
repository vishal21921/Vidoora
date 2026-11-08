package com.example.vidoora

import androidx.lifecycle.SavedStateHandle
import com.example.vidoora.core.network.SharedVideoLink
import com.example.vidoora.core.network.VideoShareAccess
import com.example.vidoora.feature.playback.VideoDetailsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@OptIn(ExperimentalCoroutinesApi::class)
class VideoDeletionUiTest {
    private val id="11111111-1111-1111-1111-111111111111"
    private val headers=headersOf(HttpHeaders.ContentType,"application/json")
    private fun details(owner: Boolean)="""{"success":true,"data":{"id":"$id","title":"Fixture","publicationState":"PROCESSED","durationSeconds":7,"visibility":"UNLISTED","published":true,"isOwner":$owner,"canPlay":true,"creatorName":"Fixture"}}"""
    @Test fun queuedDeletionUsesSharedRepositoryAndKeepsFailedIntentVisible()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var deletes=0
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Delete) deletes++
            respond(details(true),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        val uploads=TestUploadRepository()
        try {
            val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to id)),uploads)
            vm.state.first { !it.loading }
            uploads.queuedResult=com.example.vidoora.core.common.Resource.Error("Deletion pending")
            vm.deleteVideo();vm.state.first { !it.loading }
            assertFalse(vm.state.value.deleted);assertNotNull(vm.state.value.video);assertEquals(0,deletes)
            uploads.queuedResult=com.example.vidoora.core.common.Resource.Success(Unit)
            vm.deleteVideo();vm.state.first { !it.loading }
            assertTrue(vm.state.value.deleted);assertEquals(0,deletes)
        } finally { client.close();Dispatchers.resetMain() }
    }
    @Test fun confirmedDeletionClearsWatchAndOnlyThatVideoCapability()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var deletes=0
        val other="22222222-2222-2222-2222-222222222222"
        val access=VideoShareAccess().apply { remember(SharedVideoLink(id,"aaa.bbb.ccc")); remember(SharedVideoLink(other,"ddd.eee.fff")) }
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Delete) { deletes++; assertTrue(request.url.encodedPath.endsWith("/$id")); respond("""{"success":true,"data":{"accepted":true}}""",headers=headers) }
            else respond(details(true),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val uploads=TestUploadRepository()
            val vm=VideoDetailsViewModel(client,access,SavedStateHandle(mapOf("videoId" to id)),uploads); vm.state.first { !it.loading }
            assertEquals(0,deletes)
            vm.deleteVideo(); vm.state.first { !it.loading }
            assertTrue(vm.state.value.deleted); assertNull(vm.state.value.video); assertNull(access.token(id)); assertNotNull(access.token(other))
            assertEquals(listOf(id),uploads.removed)
            vm.deleteVideo(); assertEquals(1,deletes)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun failureKeepsVideoForRetryAndViewerCannotDelete()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var owner=true; var deletes=0
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Delete) { deletes++; respond("{}",HttpStatusCode.InternalServerError,headers) }
            else respond(details(owner),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository()); vm.state.first { !it.loading }
            vm.deleteVideo(); vm.state.first { !it.loading }; assertFalse(vm.state.value.deleted); assertNotNull(vm.state.value.video); assertNotNull(vm.state.value.error)
            owner=false; vm.refresh(); vm.state.first { !it.loading }; vm.deleteVideo(); assertEquals(1,deletes)
        } finally { client.close(); Dispatchers.resetMain() }
    }
}
