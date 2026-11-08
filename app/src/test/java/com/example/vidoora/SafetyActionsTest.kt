package com.example.vidoora

import androidx.lifecycle.SavedStateHandle
import com.example.vidoora.core.network.SharedVideoLink
import com.example.vidoora.core.network.VideoShareAccess
import com.example.vidoora.feature.playback.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
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
class SafetyActionsTest {
    private val id="11111111-1111-1111-1111-111111111111"
    private val owner="22222222-2222-2222-2222-222222222222"
    private val ready="""{"success":true,"data":{"id":"$id","title":"Fixture","publicationState":"PROCESSED","durationSeconds":7,"visibility":"UNLISTED","published":true,"isOwner":false,"canPlay":true,"creatorName":"Fixture"}}"""
    @Test fun blockUsesCapabilityHeaderAndClearsWatch()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var actions=0
        val client=HttpClient(MockEngine { request ->
            val action=request.url.encodedPath.endsWith("/block-uploader")
            if(action) {
                actions++; assertEquals(HttpMethod.Post,request.method)
                assertEquals("aaa.bbb.ccc",request.headers["X-Vidoora-Share"]); assertTrue(request.url.parameters.isEmpty())
            }
            respond(if(action) """{"success":true,"data":{"accepted":true}}""" else ready,headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val access=VideoShareAccess().apply { remember(SharedVideoLink(id,"aaa.bbb.ccc")) }
            val vm=VideoDetailsViewModel(client,access,SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }
            vm.blockUploader(); vm.state.first { !it.loading }
            assertNull(vm.state.value.video); assertTrue(vm.state.value.notice!!.contains("blocked")); assertEquals(1,actions)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun failedBlockDoesNotPretendToSucceedAndRevocationClearsDetails()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var status=HttpStatusCode.InternalServerError
        val client=HttpClient(MockEngine { request ->
            if(request.url.encodedPath.endsWith("/details")) respond(ready,headers=headersOf(HttpHeaders.ContentType,"application/json"))
            else respond("{}",status,headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val access=VideoShareAccess().apply { remember(SharedVideoLink(id,"aaa.bbb.ccc")) }
            val vm=VideoDetailsViewModel(client,access,SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }; vm.blockUploader(); vm.state.first { !it.loading }
            assertNotNull(vm.state.value.error); assertNull(vm.state.value.notice); assertNotNull(vm.state.value.video)
            status=HttpStatusCode.NotFound
            vm.blockUploader(); vm.state.first { !it.loading }; assertNull(vm.state.value.video)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun unblockFailureKeepsEntryAndSuccessRemovesOnlyThatEntry()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var success=false
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Get) respond("""{"success":true,"data":{"items":[{"id":"$owner","displayName":"Fixture"}]}}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            else {
                assertEquals(HttpMethod.Delete,request.method); assertTrue(request.url.encodedPath.endsWith(owner))
                respond(if(success) """{"success":true,"data":{"accepted":true}}""" else "{}",if(success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            val vm=BlockedUploadersViewModel(client); vm.state.first { !it.loading }
            vm.unblock(owner); vm.state.first { !it.loading }; assertEquals(1,vm.state.value.items.size); assertNotNull(vm.state.value.error)
            success=true; vm.unblock(owner); vm.state.first { !it.loading }; assertTrue(vm.state.value.items.isEmpty()); assertNull(vm.state.value.error)
        } finally { client.close(); Dispatchers.resetMain() }
    }
    @Test fun sharingActionsKeepTheirRoutesAfterReportRemoval()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val posts=mutableListOf<String>()
        val client=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Post) posts.add(request.url.encodedPath.substringAfterLast('/'))
            respond(ready.replace("\"isOwner\":false","\"isOwner\":true"),headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        try {
            val vm=VideoDetailsViewModel(client,VideoShareAccess(),SavedStateHandle(mapOf("videoId" to id)),TestUploadRepository())
            vm.state.first { !it.loading }
            vm.enableLinkSharing(); vm.state.first { !it.loading }
            vm.unpublish(); vm.state.first { !it.loading }
            assertEquals(listOf("publish","unpublish"),posts)
            assertNull(vm.state.value.error)
        } finally { client.close(); Dispatchers.resetMain() }
    }}


