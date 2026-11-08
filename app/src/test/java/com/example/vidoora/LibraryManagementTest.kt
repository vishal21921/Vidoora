package com.example.vidoora

import androidx.lifecycle.SavedStateHandle
import com.example.vidoora.core.network.VideoShareAccess
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
class LibraryManagementTest {
    private val id="11111111-1111-1111-1111-111111111111"
    private val headers=headersOf(HttpHeaders.ContentType,"application/json")
    private fun page(ids: List<String>,next: String?=null)=Json.encodeToString(ApiEnvelope(true,LibraryPageDto(ids.map {
        LibraryVideoDto(it,"Video $it","PROCESSED",7,"PRIVATE")
    },next)))
    private fun details(title: String="Original",owner: Boolean=true)=Json.encodeToString(ApiEnvelope(true,VideoDetailsDto(
        id,title,"Original description","PROCESSED",7,"UNLISTED",true,owner,true,"Fixture")))

    @Test fun returningReconcilesWithoutDiscardingLoadedPagesOrCursor()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var pageCalls=0
        var snapshots=0
        val client=HttpClient(MockEngine { request ->
            if(request.url.encodedPath.endsWith("/status")) {
                snapshots++
                respond(Json.encodeToString(ApiEnvelope(true,listOf(
                    LibraryVideoDto("one","Edited","PROCESSED",7,"PRIVATE"),
                    LibraryVideoDto("two","Second","PROCESSED",7,"PRIVATE")))),headers=headers)
            } else {
                pageCalls++
                respond(if(request.url.parameters["cursor"]==null) page(listOf("one"),"page2")
                    else page(listOf("two"),"page3"),headers=headers)
            }
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoLibraryViewModel(client)
            vm.state.first { !it.loading }; vm.loadMore(); vm.state.first { !it.loading }
            vm.startObserving(); vm.onResume(); vm.state.first { it.videos.first().title=="Edited" }
            assertEquals(2,pageCalls); assertEquals(1,snapshots)
            assertEquals(listOf("one","two"),vm.state.value.videos.map {it.id})
            assertEquals("Edited",vm.state.value.videos.first().title)
            assertEquals("page3",vm.state.value.nextCursor)
            vm.stopObserving()
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun paginationUsesAppliedSearchAndPreservesPageForRetry()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var failMore=true; var moreCalls=0
        val client=HttpClient(MockEngine { request ->
            val cursor=request.url.parameters["cursor"]
            if(cursor!=null) {
                assertEquals("page2",cursor); assertEquals("family%_",request.url.parameters["q"]); moreCalls++
                if(failMore) respond("{}",HttpStatusCode.InternalServerError,headers)
                else respond(page(listOf("one","two")),headers=headers)
            } else respond(page(listOf("one"),"page2"),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoLibraryViewModel(client); vm.state.first { !it.loading }
            vm.setSearchText(" family%_ "); vm.search(); vm.state.first { !it.loading }
            vm.setSearchText("Unsubmitted typing")
            vm.loadMore(); vm.state.first { !it.loading }
            assertEquals(listOf("one"),vm.state.value.videos.map { it.id }); assertEquals("page2",vm.state.value.nextCursor); assertNotNull(vm.state.value.error)
            failMore=false; vm.loadMore(); vm.state.first { !it.loading }
            assertEquals(listOf("one","two"),vm.state.value.videos.map { it.id }); assertNull(vm.state.value.nextCursor)
            assertEquals("Unsubmitted typing",vm.state.value.searchText)
            vm.loadMore(); assertEquals(2,moreCalls)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun changingSearchCancelsStalePageAndResetsCursor()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val oldStarted=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine { request ->
            val q=request.url.parameters["q"]
            if(q=="old") { oldStarted.complete(Unit); release.await() }
            assertNull(request.url.parameters["cursor"])
            respond(page(listOf(q ?: "initial"),if(q==null) "old-cursor" else null),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoLibraryViewModel(client); vm.state.first { !it.loading }
            vm.setSearchText("old"); vm.search(); oldStarted.await()
            vm.setSearchText("new"); vm.search(); vm.state.first { !it.loading && it.appliedSearch=="new" }
            release.complete(Unit); advanceUntilIdle()
            assertEquals(listOf("new"),vm.state.value.videos.map { it.id }); assertNull(vm.state.value.nextCursor)
            vm.setSearchText(""); vm.search(); vm.state.first { !it.loading }
            assertEquals("",vm.state.value.appliedSearch); assertEquals(listOf("initial"),vm.state.value.videos.map { it.id })
        } finally { release.complete(Unit); client.close(); Dispatchers.resetMain() }
    }
    @Test fun metadataSaveFailureRetainsDraftAndSuccessUpdatesDetails()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var failSave=true; var saves=0
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Patch) {
                saves++; assertTrue(request.url.encodedPath.endsWith("/$id/details")); assertTrue(request.url.parameters.isEmpty())
                if(failSave) respond("{}",HttpStatusCode.InternalServerError,headers)
                else respond(details("Edited"),headers=headers)
            } else respond(details(),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }; vm.startEditing(); vm.setEditTitle(" Edited "); vm.setEditDescription("Changed description")
            vm.saveDetails(); vm.state.first { !it.loading }
            assertTrue(vm.state.value.editing); assertEquals(" Edited ",vm.state.value.editTitle); assertNotNull(vm.state.value.editError)
            assertEquals("Original",vm.state.value.video?.title)
            failSave=false; vm.saveDetails(); vm.state.first { !it.loading }
            assertFalse(vm.state.value.editing); assertEquals("Edited",vm.state.value.video?.title); assertNotNull(vm.state.value.notice); assertEquals(2,saves)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun editValidationAndCancelDoNotSendChanges()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var writes=0
        val client=HttpClient(MockEngine { request ->
            if(request.method!=HttpMethod.Get) writes++
            respond(details(),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }; vm.startEditing()
            vm.setEditTitle(" "); vm.saveDetails(); assertNotNull(vm.state.value.editError)
            vm.setEditTitle("x".repeat(129)); vm.saveDetails(); assertNotNull(vm.state.value.editError)
            vm.setEditTitle("Okay"); vm.setEditDescription("x".repeat(5001)); vm.saveDetails(); assertNotNull(vm.state.value.editError)
            vm.cancelEditing(); assertFalse(vm.state.value.editing); assertEquals("Original",vm.state.value.video?.title); assertEquals(0,writes)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun viewersCannotStartEditingAndDeniedSaveClearsStaleDetails()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var isOwner=false; var writes=0
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Patch) { writes++; respond("{}",HttpStatusCode.NotFound,headers) }
            else respond(details(owner=isOwner),headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }; vm.startEditing(); vm.saveDetails()
            assertFalse(vm.state.value.editing); assertEquals(0,writes)
            isOwner=true; vm.refresh(); vm.state.first { !it.loading }; vm.startEditing(); vm.setEditTitle("Change")
            vm.saveDetails(); vm.state.first { !it.loading }
            assertNull(vm.state.value.video); assertFalse(vm.state.value.editing); assertNotNull(vm.state.value.error); assertEquals(1,writes)
        } finally { client.close(); Dispatchers.resetMain() }
    }
}
