package com.example.vidoora

import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.feature.playback.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFiltersTest {
    private val headers=headersOf(HttpHeaders.ContentType,"application/json")
    private fun page(id: String,next: String?=null)=Json.encodeToString(ApiEnvelope(true,LibraryPageDto(listOf(LibraryVideoDto(id,id,"PROCESSED",7,"PRIVATE")),next)))
    @Test fun paginationRefreshAndResetKeepTheSelectedViewTogether()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var finalView=false; var resets=0
        val client=HttpClient(MockEngine { request ->
            val q=request.url.parameters
            if(finalView) {
                assertEquals("OLDEST",q["sort"]); assertEquals("READY",q["state"]); assertEquals("PRIVATE",q["sharing"])
                assertEquals("family",q["q"])
            }
            if(q["cursor"]!=null) { assertEquals("tail",q["cursor"]); respond(page("older"),headers=headers) }
            else { resets++; respond(page("first","tail"),headers=headers) }
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoLibraryViewModel(client); vm.state.first { !it.loading }
            vm.setSearchText("family"); vm.search(); vm.state.first { !it.loading }
            vm.setSort("OLDEST"); vm.state.first { !it.loading }
            vm.setProcessingFilter("READY"); vm.state.first { !it.loading }
            vm.setSharingFilter("PRIVATE"); vm.state.first { !it.loading }; finalView=true
            vm.setSearchText("unsubmitted draft")
            vm.loadMore(); vm.state.first { !it.loading }
            assertEquals(listOf("first","older"),vm.state.value.videos.map { it.id })
            vm.refresh(); vm.state.first { !it.loading }; assertEquals(1,vm.state.value.videos.size)
            assertEquals("unsubmitted draft",vm.state.value.searchText)
            finalView=false; vm.resetView(); vm.state.first { !it.loading }
            assertEquals("NEWEST",vm.state.value.sortOrder); assertEquals("ALL",vm.state.value.processingFilter)
            assertEquals("ALL",vm.state.value.sharingFilter); assertEquals("",vm.state.value.appliedSearch); assertEquals("",vm.state.value.searchText)
            assertTrue(resets>=6)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun changingFilterCancelsOldPageAndDoesNotMixResults()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine { request ->
            val params=request.url.parameters
            if(params["cursor"]!=null) { started.complete(Unit); release.await(); respond(page("stale"),headers=headers) }
            else if(params["state"]=="READY") respond(page("ready-only"),headers=headers)
            else respond(page("all","old-next"),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoLibraryViewModel(client); vm.state.first { !it.loading }
            vm.loadMore(); started.await()
            vm.setProcessingFilter("READY"); vm.state.first { !it.loading }
            release.complete(Unit); advanceUntilIdle()
            assertEquals(listOf("ready-only"),vm.state.value.videos.map { it.id }); assertNull(vm.state.value.nextCursor)
        } finally { release.complete(Unit); client.close(); Dispatchers.resetMain() }
    }
}
