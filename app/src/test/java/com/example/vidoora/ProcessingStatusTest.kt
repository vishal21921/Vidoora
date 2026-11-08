package com.example.vidoora

import androidx.lifecycle.SavedStateHandle
import com.example.vidoora.core.network.VideoShareAccess
import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.feature.playback.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
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
class ProcessingStatusTest {
    private val headers=headersOf(HttpHeaders.ContentType,"application/json")
    private inline fun <reified T> body(value: T)=Json.encodeToString(ApiEnvelope(true,value))
    private fun detail(state: String,owner: Boolean=true)=VideoDetailsDto("v","Video",publicationState=state,durationSeconds=if(state=="PROCESSED") 40 else 0,
        visibility="PRIVATE",published=false,isOwner=owner,canPlay=state=="PROCESSED",creatorName="Fixture")

    @Test fun pollingIsForegroundOnlyBacksOffAndNeverOverlaps()=runTest {
        var needed=true; var calls=0
        val poller=ProcessingStatusPoller(backgroundScope,{ needed }) { calls++; false }
        advanceTimeBy(60_000); assertEquals(0,calls)
        poller.start(); poller.start()
        advanceTimeBy(5_000); runCurrent(); assertEquals(1,calls)
        advanceTimeBy(9_999); runCurrent(); assertEquals(1,calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2,calls)
        needed=false; advanceTimeBy(40_000); runCurrent(); assertEquals(2,calls)
        poller.stop(); needed=true; advanceTimeBy(60_000); runCurrent(); assertEquals(2,calls)
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val slow=ProcessingStatusPoller(backgroundScope,{ true }) { calls++; entered.complete(Unit); release.await(); true }
        slow.start(); advanceTimeBy(5_000); runCurrent(); entered.await()
        advanceTimeBy(30_000); runCurrent(); assertEquals(3,calls)
        slow.stop(); release.complete(Unit); runCurrent(); assertEquals(3,calls)
    }
    @Test fun libraryUpdatesPreserveLoadedPagesCursorDraftAndAppliedFilters()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var posts=0
        val first=LibraryVideoDto("first","Family","UPLOADED",0,"PRIVATE")
        val older=LibraryVideoDto("older","Family older","PROCESSING",0,"PRIVATE")
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Post) {
                posts++
                val sent=Json.decodeFromString<OwnerVideoStatusRequestDto>((request.body as TextContent).text)
                assertEquals(setOf("first","older"),sent.ids.toSet())
                assertEquals("family",sent.search); assertEquals("OLDEST",sent.sort); assertEquals("PRIVATE",sent.sharing); assertEquals("f",sent.folder)
                respond(body(listOf(first.copy(publicationState="PROCESSED",durationSeconds=40,thumbnailAvailable=true),older)),headers=headers)
            } else respond(body(if(request.url.parameters["cursor"]==null) LibraryPageDto(listOf(first),"tail") else LibraryPageDto(listOf(older),"tail2")),headers=headers)
        }) { install(ContentNegotiation) { json() } }
        val vm=VideoLibraryViewModel(client)
        try {
            vm.state.first { !it.loading }; vm.setSearchText("family"); vm.search(); vm.state.first { !it.loading }
            vm.setSort("OLDEST"); vm.state.first { !it.loading }; vm.setSharingFilter("PRIVATE"); vm.state.first { !it.loading }
            vm.setFolder("f"); vm.state.first { !it.loading }; vm.loadMore(); vm.state.first { !it.loading }
            vm.setSearchText("unsubmitted draft"); vm.setVisibleVideos(listOf("first","older")); vm.startObserving()
            advanceTimeBy(5_000); runCurrent(); vm.state.first { it.videos.first().publicationState=="PROCESSED" }
            assertEquals(listOf("first","older"),vm.state.value.videos.map { it.id })
            assertEquals("tail2",vm.state.value.nextCursor); assertEquals("unsubmitted draft",vm.state.value.searchText)
            assertTrue(vm.state.value.videos.first().thumbnailAvailable); assertFalse(vm.state.value.loading); assertEquals(1,posts)
        } finally { vm.stopObserving(); client.close(); Dispatchers.resetMain() }
    }
    @Test fun snapshotsRemoveOnlyRequestedRowsThatLeaveTheProcessingView()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Post) respond(body(emptyList<LibraryVideoDto>()),headers=headers)
            else respond(body(LibraryPageDto(listOf(LibraryVideoDto("visible","Visible","UPLOADED",0,"PRIVATE"),LibraryVideoDto("offscreen","Offscreen","UPLOADED",0,"PRIVATE")),"cursor")),headers=headers)
        }) { install(ContentNegotiation) { json() } }
        val vm=VideoLibraryViewModel(client)
        try {
            vm.state.first { !it.loading }; vm.setProcessingFilter("PROCESSING"); vm.state.first { !it.loading }
            vm.setVisibleVideos(listOf("visible")); vm.startObserving()
            advanceTimeBy(5_000); runCurrent(); vm.state.first { it.videos.size==1 }
            assertEquals("offscreen",vm.state.value.videos.single().id); assertEquals("cursor",vm.state.value.nextCursor)
        } finally { vm.stopObserving(); client.close(); Dispatchers.resetMain() }
    }
    @Test fun libraryKeepsCachedStateOnNetworkFailureAndIgnoresOldViewResponses()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var phase=0
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Post) {
                if(phase==0) respond("offline",HttpStatusCode.ServiceUnavailable) else {
                    entered.complete(Unit); release.await()
                    respond(body(listOf(LibraryVideoDto("pending","Old","PROCESSED",20,"PRIVATE"))),headers=headers)
                }
            } else respond(body(LibraryPageDto(listOf(LibraryVideoDto(if(request.url.parameters["state"]=="READY") "new-view" else "pending","Fixture",if(request.url.parameters["state"]=="READY") "PROCESSED" else "UPLOADED",0,"PRIVATE")))),headers=headers)
        }) { install(ContentNegotiation) { json() } }
        val vm=VideoLibraryViewModel(client)
        try {
            vm.state.first { !it.loading }; vm.setVisibleVideos(listOf("pending")); vm.startObserving()
            advanceTimeBy(5_000); runCurrent(); vm.state.first { it.statusError!=null }
            assertEquals("UPLOADED",vm.state.value.videos.single().publicationState)
            phase=1; advanceTimeBy(10_000); runCurrent(); entered.await()
            vm.setProcessingFilter("READY"); vm.state.first { !it.loading }
            release.complete(Unit); runCurrent()
            assertEquals("new-view",vm.state.value.videos.single().id); assertNull(vm.state.value.statusError)
            vm.stopObserving(); advanceTimeBy(120_000); runCurrent()
        } finally { vm.stopObserving(); release.complete(Unit); client.close(); Dispatchers.resetMain() }
    }
    @Test fun detailsBecomeReadyWithoutPublishingAndStopPollingWhenReady()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var gets=0
        val client=HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get,request.method); gets++
            respond(body(detail(when(gets) { 1 -> "UPLOADED"; 2 -> "PROCESSING"; else -> "PROCESSED" })),headers=headers)
        }) { install(ContentNegotiation) { json() } }
        val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to "v")),TestUploadRepository())
        try {
            vm.state.first { !it.loading }; vm.startObserving()
            advanceTimeBy(5_000); runCurrent(); vm.state.first { it.video?.publicationState=="PROCESSING" }
            advanceTimeBy(5_000); runCurrent(); vm.state.first { it.video?.canPlay==true }
            assertFalse(vm.state.value.video!!.published); assertEquals("Ready to share",VideoProcessingStatus.label(vm.state.value.video!!.publicationState))
            advanceTimeBy(60_000); runCurrent(); assertEquals(3,gets)
        } finally { vm.stopObserving(); client.close(); Dispatchers.resetMain() }
    }
    @Test fun inFlightDetailPollCannotOverwriteEditsOrSurvivePause()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var gets=0
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine {
            gets++
            if(gets==2) { entered.complete(Unit); release.await() }
            respond(body(detail(if(gets==1) "UPLOADED" else "PROCESSED")),headers=headers)
        }) { install(ContentNegotiation) { json() } }
        val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to "v")),TestUploadRepository())
        try {
            vm.state.first { !it.loading }; vm.startObserving(); advanceTimeBy(5_000); runCurrent(); entered.await()
            vm.startEditing(); vm.setEditTitle("Unsaved draft"); release.complete(Unit); runCurrent()
            assertTrue(vm.state.value.editing); assertEquals("Unsaved draft",vm.state.value.editTitle); assertFalse(vm.state.value.video!!.canPlay)
            advanceTimeBy(30_000); runCurrent(); assertEquals(2,gets)
            vm.cancelEditing(); vm.stopObserving(); advanceTimeBy(60_000); runCurrent(); assertEquals(2,gets)
            vm.startObserving(); advanceTimeBy(5_000); runCurrent(); vm.state.first { it.video?.canPlay==true }
        } finally { vm.stopObserving(); release.complete(Unit); client.close(); Dispatchers.resetMain() }
    }
    @Test fun failureIsActionableAndGuestDetailsDoNotPoll()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var gets=0
        val client=HttpClient(MockEngine { gets++; respond(body(detail("UPLOADED",owner=false)),headers=headers) }) { install(ContentNegotiation) { json() } }
        val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to "v")),TestUploadRepository())
        try {
            vm.state.first { !it.loading }; vm.startObserving(); advanceTimeBy(60_000); runCurrent()
            assertEquals(1,gets); assertEquals("Processing failed",VideoProcessingStatus.label("PROCESSING_FAILED"))
            assertTrue(VideoProcessingStatus.guidance("PROCESSING_FAILED")!!.contains("processor log"))
            assertFalse(VideoProcessingStatus.pending("PROCESSED")); assertFalse(vm.state.value.video!!.canPlay)
        } finally { vm.stopObserving(); client.close(); Dispatchers.resetMain() }
    }
    @Test fun unauthorizedSnapshotsClearOwnerRowsAndStopAfterPause()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var posts=0
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Post) { posts++; respond("unauthorized",HttpStatusCode.Unauthorized) }
            else respond(body(LibraryPageDto(listOf(LibraryVideoDto("pending","Private","UPLOADED",0,"PRIVATE")),"tail")),headers=headers)
        }) { install(ContentNegotiation) { json() } }
        val vm=VideoLibraryViewModel(client)
        try {
            vm.state.first { !it.loading }; vm.setVisibleVideos(listOf("pending")); vm.startObserving()
            advanceTimeBy(5_000); runCurrent(); vm.state.first { it.error!=null }
            assertTrue(vm.state.value.videos.isEmpty()); assertNull(vm.state.value.nextCursor)
            vm.stopObserving(); advanceTimeBy(120_000); runCurrent(); assertEquals(1,posts)
        } finally { vm.stopObserving(); client.close(); Dispatchers.resetMain() }
    }
}

