package com.example.vidoora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.core.security.SecureStorageManager
import com.example.vidoora.feature.playback.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class WatchProgressTest {
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var storage: SecureStorageManager
    private lateinit var store: WatchProgressStore
    private val video="11111111-1111-1111-1111-111111111111"
    private val a="22222222-2222-2222-2222-222222222222"
    private val b="33333333-3333-3333-3333-333333333333"
    private val headers=headersOf(HttpHeaders.ContentType,"application/json")
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        prefs=context.getSharedPreferences("watch_fixture_${UUID.randomUUID()}",Context.MODE_PRIVATE)
        storage=SecureStorageManager(context,prefs); store=WatchProgressStore(storage)
    }
    private fun access(subject: String): String {
        val payload=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$subject","type":"access"}""".toByteArray())
        return "fixture.$payload.fixture"
    }
    private fun details(state: String="PROCESSED",seconds: Int=60)=VideoDetailsDto(video,"Fixture",publicationState=state,durationSeconds=seconds,
        visibility="UNLISTED",published=true,isOwner=false,canPlay=state=="PROCESSED",creatorName="Fixture")
    private fun body(dto: VideoDetailsDto)=Json.encodeToString(ApiEnvelope(true,dto))
    private suspend fun seed(position: Long=10_000,duration: Long=60_000): WatchProgressHandle {
        val handle=store.begin("guest",video)!!
        assertTrue(store.save(handle,position,duration))
        return handle
    }
    @Test fun historyReopensLatestShareAndSeparatesGuestsFromAccounts() = runTest {
        val history = WatchHistoryStore(storage)
        suspend fun record(store: WatchHistoryStore, identity: String, title: String, capability: String?) {
            val revision = store.changes.value
            store.record(identity, video, title, capability)
            store.changes.first { it > revision }
        }
        record(history, "guest", "Original title", "s1.abc.first")
        record(history, "guest", "Latest title", "s1.abc.latest")
        val restored = WatchHistoryStore(storage)
        assertEquals("s1.abc.latest", restored.load("guest").single().capability)
        storage.saveTokens(access(a), "fixture-refresh-a")
        assertTrue(restored.load("guest").isEmpty())
        assertTrue(restored.load("user:$a").isEmpty())
        record(restored, "user:$a", "Account video", null)
        storage.endSession()
        assertEquals("Latest title", restored.load("guest").single().title)
        restored.remove("guest", video)
        assertTrue(restored.load("guest").isEmpty())
        storage.saveTokens(access(a), "fixture-refresh-a")
        assertEquals("Account video", restored.load("user:$a").single().title)
    }
    @Test fun guestAndAccountsHaveSeparateDurableCheckpointsWithoutStoredVideoIdsOrLinks()=runTest {
        val guest=seed(30_000)
        storage.saveTokens(access(a),"fixture-refresh-a")
        assertEquals("user:$a",storage.watchIdentity())
        val first=store.begin("user:$a",video)!!
        assertNull(store.load(first).checkpoint); assertTrue(store.save(first,10_000,60_000))
        assertFalse(store.save(guest,40_000,60_000))
        storage.saveTokens(access(b),"fixture-refresh-b")
        val second=store.begin("user:$b",video)!!
        assertNull(store.load(second).checkpoint); assertTrue(store.save(second,20_000,60_000))
        storage.saveTokens(access(a),"fixture-refresh-a")
        val restored=WatchProgressStore(storage)
        assertEquals(10_000L,restored.load(restored.begin("user:$a",video)!!).checkpoint!!.positionMs)
        storage.endSession()
        assertEquals(30_000L,restored.load(restored.begin("guest",video)!!).checkpoint!!.positionMs)
        val raw=prefs.getString("watch_progress_v1",null)!!
        for(secret in listOf(video,a,b,"fixture-refresh-a","vidoora://","Fixture","guest")) assertFalse(raw.contains(secret))
    }
    @Test fun boundsExpiryAndCorruptionAffectOnlyProgress()=runTest {
        val now=System.currentTimeMillis()-1_000
        val ids=(0..100).map { UUID.randomUUID().toString() }
        val handles=ids.mapIndexed { i,id -> store.begin("guest",id)!!.also { assertTrue(store.save(it,10_000,60_000,now=now+i)) } }
        assertNull(store.load(handles.first(),now+101).checkpoint)
        assertNotNull(store.load(handles.last(),now+101).checkpoint)
        assertEquals(100,org.json.JSONObject(prefs.getString("watch_progress_v1",null)!!).getJSONObject("entries").length())
        assertNull(store.load(handles.last(),now+WatchProgressPolicy.MAX_AGE_MS+2_000).checkpoint)
        prefs.edit().putString("watch_progress_v1","broken").putString("unrelated","keep").commit()
        assertFalse(store.load(handles.last()).available)
        assertTrue(store.save(handles.last(),12_000,60_000))
        assertEquals("keep",prefs.getString("unrelated",null))
        assertEquals(12_000L,store.load(handles.last()).checkpoint!!.positionMs)
    }
    @Test fun unknownTimelinesDoNotEraseWhileCompletionAndStartOverClearProgress()=runTest {
        val handle=seed()
        assertFalse(store.save(handle,0,androidx.media3.common.C.TIME_UNSET))
        assertEquals(10_000L,store.load(handle).checkpoint!!.positionMs)
        assertTrue(store.save(handle,60_001,60_000,ended=true)); assertNull(store.load(handle).checkpoint)
        assertTrue(store.save(handle,10_000,60_000)); assertTrue(store.clear(handle)); assertNull(store.load(handle).checkpoint)
        assertTrue(store.save(handle,59_000,60_000)); assertNull(store.load(handle).checkpoint)
        assertNull(WatchProgressPolicy.candidate(WatchCheckpoint(1_000,60_000,System.currentTimeMillis()),60_000))
        assertNull(WatchProgressPolicy.candidate(WatchCheckpoint(10_000,60_000,System.currentTimeMillis()),180_000))
        assertEquals("1:02:03",WatchProgressPolicy.time(3_723_000))
    }
    @Test fun olderPlayerCannotUndoStartOverOrWriteIntoAnotherAccount()=runTest {
        val old=seed(30_000)
        val next=store.begin("guest",video)!!
        assertTrue(store.clear(next)); assertFalse(store.save(old,35_000,60_000))
        assertNull(store.load(next).checkpoint)
        storage.saveTokens(access(a),"fixture-refresh-a")
        assertFalse(store.save(next,30_000,60_000)); assertFalse(store.clear(next))
        assertNull(store.load(store.begin("user:$a",video)!!).checkpoint)
    }
    @Test fun freshAccessCheckPrecedesPromptAndOnlyPreparedMediaCanSave()=runTest {
        seed()
        var requests=0
        val client=HttpClient(MockEngine { request ->
            requests++; assertEquals("/api/v1/videos/$video/details",request.url.encodedPath)
            assertEquals("aaa.bbb.ccc",request.headers["X-Vidoora-Share"])
            respond(body(details()),headers=headers)
        }) { install(ContentNegotiation) { json() } }
        try {
            val session=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,"aaa.bbb.ccc")
            session.open(); assertEquals(1,requests)
            assertEquals(10_000L,session.state.value.promptPositionMs); assertNull(session.state.value.startPositionMs)
            assertNull(session.checkpoint(0,60_000))
            session.resume(); assertEquals(10_000L,session.state.value.startPositionMs)
            assertNull(session.checkpoint(20_000,60_000))
            session.mediaPrepared(); session.checkpoint(20_000,60_000)!!.join()
            val stored=store.load(store.begin("guest",video)!!).checkpoint!!
            assertEquals(20_000L,stored.positionMs)
            assertFalse(prefs.getString("watch_progress_v1",null)!!.contains("aaa.bbb.ccc"))
        } finally { client.close() }
    }
    @Test fun revokedExpiredAndNotReadyVideosNeverOfferOrOverwriteSavedProgress()=runTest {
        val original=seed()
        var status=HttpStatusCode.NotFound; var dto=details()
        val client=HttpClient(MockEngine { respond(body(dto),status,headers) }) { install(ContentNegotiation) { json() } }
        try {
            for(code in listOf(HttpStatusCode.NotFound,HttpStatusCode.Unauthorized,HttpStatusCode.Forbidden,HttpStatusCode.ServiceUnavailable)) {
                status=code
                val session=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,"revoked.capability.fixture")
                session.open(); assertNotNull(session.state.value.error); assertNull(session.state.value.promptPositionMs)
                assertEquals(code.value in setOf(401,403,404),session.state.value.unavailable)
                session.mediaPrepared(); assertNull(session.checkpoint(0,60_000))
                assertEquals(10_000L,store.load(original).checkpoint!!.positionMs)
            }
            status=HttpStatusCode.OK; dto=details("UPLOADED")
            val session=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,null)
            session.open(); assertNotNull(session.state.value.error); assertNull(session.state.value.startPositionMs)
            assertTrue(session.state.value.unavailable)
            assertEquals(10_000L,store.load(original).checkpoint!!.positionMs)
        } finally { client.close() }
    }
    @Test fun startOverClearsSavedPositionAndLeavingAnUnchosenPromptKeepsIt()=runTest {
        seed()
        val client=HttpClient(MockEngine { respond(body(details()),headers=headers) }) { install(ContentNegotiation) { json() } }
        try {
            val unchosen=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,null)
            unchosen.open(); assertNotNull(unchosen.state.value.promptPositionMs)
            assertNull(unchosen.checkpoint(0,60_000))
            val next=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,null)
            next.open(); assertEquals(10_000L,next.state.value.promptPositionMs)
            assertTrue(next.state.value.viewer); assertEquals(60_000L,next.state.value.durationMs)
            next.startOver(); assertEquals(0L,next.state.value.startPositionMs); assertTrue(next.state.value.viewer); assertEquals(60_000L,next.state.value.durationMs)
            assertNull(store.load(store.begin("guest",video)!!).checkpoint)
        } finally { client.close() }
    }
    @Test fun retryRechecksAccessAndChangedDurationsDoNotResumeTheWrongTimeline()=runTest {
        seed()
        var status=HttpStatusCode.OK; var requests=0
        val client=HttpClient(MockEngine { requests++; respond(body(details(seconds=180)),status,headers) }) { install(ContentNegotiation) { json() } }
        try {
            val session=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,null)
            session.open(); assertNull(session.state.value.promptPositionMs); assertEquals(0L,session.state.value.startPositionMs)
            session.mediaPrepared(); session.mediaFailed(); assertNull(session.checkpoint(20_000,180_000))
            status=HttpStatusCode.NotFound; session.open(retryPosition=20_000)
            assertNotNull(session.state.value.error); assertNull(session.state.value.startPositionMs)
            status=HttpStatusCode.OK; session.open(retryPosition=20_000)
            assertEquals(20_000L,session.state.value.startPositionMs); assertNull(session.state.value.promptPositionMs)
            val priorAttempt = session.state.value.attempt
            session.open(retryPosition=20_000)
            assertEquals(20_000L, session.state.value.startPositionMs)
            assertTrue(session.state.value.attempt > priorAttempt)
            assertEquals(4,requests)
        } finally { client.close() }
    }
    @Test fun identityChangeDuringAccessCheckCannotRevealThePreviousGuestPosition()=runTest {
        seed()
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine { entered.complete(Unit); release.await(); respond(body(details()),headers=headers) }) { install(ContentNegotiation) { json() } }
        try {
            val session=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,null)
            val opening=async { session.open() }; entered.await()
            storage.saveTokens(access(a),"fixture-refresh-a"); release.complete(Unit); opening.await()
            assertNull(session.state.value.promptPositionMs); assertNull(session.state.value.startPositionMs)
            assertNull(session.checkpoint(20_000,60_000))
        } finally { release.complete(Unit); client.close() }
    }
    @Test fun overlappingOpenActionsHaveOnlyOneAccessRequest()=runTest {
        seed()
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var requests=0
        val client=HttpClient(MockEngine { requests++; entered.complete(Unit); release.await(); respond(body(details()),headers=headers) }) { install(ContentNegotiation) { json() } }
        try {
            val session=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,null)
            val first=async { session.open() }; entered.await()
            session.open(); assertEquals(1,requests)
            release.complete(Unit); first.await(); assertEquals(10_000L,session.state.value.promptPositionMs)
        } finally { release.complete(Unit); client.close() }
    }
    @Test fun uploaderWatchingThroughLinkIsEligibleForViewerAdvertising()=runTest {
        val client=HttpClient(MockEngine { respond(body(details().copy(isOwner=true)),headers=headers) }) { install(ContentNegotiation) { json() } }
        try {
            val session=WatchProgressSession(client,store,"guest",storage::watchIdentity,video,"owner.share.fixture")
            session.open()
            assertEquals(0L,session.state.value.startPositionMs)
            assertTrue(session.state.value.viewer)
            assertFalse(session.state.value.unavailable)
        } finally { client.close() }
    }
}





