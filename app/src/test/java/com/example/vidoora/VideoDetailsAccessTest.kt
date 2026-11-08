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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class VideoDetailsAccessTest {
    @Test fun revokedDetailsClearStaleWatchPermissionAndUseHeaderCapability()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val id="11111111-1111-1111-1111-111111111111"
        val access=VideoShareAccess().apply { remember(SharedVideoLink(id,"aaa.bbb.ccc")) }
        var denied=false; var usedHeader=false
        val client=HttpClient(MockEngine { request ->
            usedHeader=request.headers["X-Vidoora-Share"]=="aaa.bbb.ccc" && request.url.parameters.isEmpty()
            if(denied) respond("{}",HttpStatusCode.NotFound,headersOf(HttpHeaders.ContentType,"application/json"))
            else respond("""{"success":true,"data":{"id":"$id","title":"Fixture","publicationState":"PROCESSED","durationSeconds":7,"visibility":"UNLISTED","published":true,"isOwner":false,"canPlay":true,"creatorName":"Fixture"}}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val viewModel=VideoDetailsViewModel(client,access,SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            val ready=viewModel.state.first { !it.loading }; assertTrue(usedHeader); assertTrue(ready.video?.canPlay==true)
            denied=true; viewModel.refresh(); viewModel.state.first { !it.loading }
            assertNull(viewModel.state.value.video); assertNotNull(viewModel.state.value.error)
        } finally { client.close(); Dispatchers.resetMain() }
    }
}

