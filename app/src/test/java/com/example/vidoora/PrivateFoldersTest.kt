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
class PrivateFoldersTest {
    private val headers=headersOf(HttpHeaders.ContentType,"application/json")
    private val folder="22222222-2222-2222-2222-222222222222"
    private val video="11111111-1111-1111-1111-111111111111"
    @Test fun folderMutationsDoNotPretendSuccessOnFailure()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fail=true
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Get) respond("""{"success":true,"data":[]}""",headers=headers)
            else if(fail) respond("{}",HttpStatusCode.InternalServerError,headers)
            else if(request.method==HttpMethod.Delete) respond("""{"success":true,"data":{"accepted":true}}""",headers=headers)
            else respond("""{"success":true,"data":{"id":"$folder","name":"Family"}}""",headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=FoldersViewModel(client); vm.state.first { !it.loading }
            vm.save("Family",null); vm.state.first { !it.loading }; assertTrue(vm.state.value.items.isEmpty()); assertEquals(0,vm.state.value.change); assertNotNull(vm.state.value.error)
            fail=false; vm.save("Family",null); vm.state.first { !it.loading }; assertEquals(folder,vm.state.value.items.single().id); assertEquals(1,vm.state.value.change)
            fail=true; vm.remove(folder); vm.state.first { !it.loading }; assertEquals(1,vm.state.value.items.size)
            fail=false; vm.remove(folder); vm.state.first { !it.loading }; assertTrue(vm.state.value.items.isEmpty()); assertEquals(2,vm.state.value.change)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun movingVideoRetainsSelectionForRetryAndUpdatesOnlyAfterAcknowledgement()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fail=true; var moved: String?=null
        fun details()=Json.encodeToString(ApiEnvelope(true,VideoDetailsDto(video,"Fixture",null,"PROCESSED",7,"UNLISTED",true,true,true,"Owner",moved)))
        val client=HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/folders") -> respond("""{"success":true,"data":[{"id":"$folder","name":"Family"}]}""",headers=headers)
                request.method==HttpMethod.Put -> {
                    assertTrue(request.url.encodedPath.endsWith("/$video/folder"))
                    if(fail) respond("{}",HttpStatusCode.InternalServerError,headers) else { moved=folder; respond(details(),headers=headers) }
                }
                else -> respond(details(),headers=headers)
            }
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to video)),TestUploadRepository()); vm.state.first { !it.loading }
            vm.startMoving(); vm.state.first { !it.loading }; vm.selectFolder(folder); vm.saveFolderMove(); vm.state.first { !it.loading }
            assertTrue(vm.state.value.moving); assertEquals(folder,vm.state.value.selectedFolder); assertNull(vm.state.value.video?.folderId); assertNotNull(vm.state.value.moveError)
            fail=false; vm.saveFolderMove(); vm.state.first { !it.loading }
            assertFalse(vm.state.value.moving); assertEquals(folder,vm.state.value.video?.folderId); assertTrue(vm.state.value.video?.published==true)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun selectedFolderIsKeptForPagingAndResetStartsFromAllVideos()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var expected="ALL"
        val client=HttpClient(MockEngine { request ->
            assertEquals(expected,request.url.parameters["folder"])
            respond("""{"success":true,"data":{"items":[],"nextCursor":null}}""",headers=headers)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=VideoLibraryViewModel(client); vm.state.first { !it.loading }
            expected=folder; vm.setFolder(folder); vm.state.first { !it.loading }; vm.refresh(); vm.state.first { !it.loading }
            assertEquals(folder,vm.state.value.folderFilter)
            expected="ALL"; vm.resetView(); vm.state.first { !it.loading }; assertEquals("ALL",vm.state.value.folderFilter)
        } finally { client.close(); Dispatchers.resetMain() }
    }
}
