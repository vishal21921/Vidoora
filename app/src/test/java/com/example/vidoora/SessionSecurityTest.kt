package com.example.vidoora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vidoora.core.network.ApiConfig
import com.example.vidoora.core.security.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class SessionSecurityTest {
    private lateinit var context: Context
    private lateinit var store: SecureStorageManager
    private lateinit var prefs: android.content.SharedPreferences
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        prefs=context.getSharedPreferences("session_fixture_${UUID.randomUUID()}",Context.MODE_PRIVATE)
        store=SecureStorageManager(context,prefs)
        store.saveTokens("access0","refresh0")
    }
    @Test fun concurrentRefreshesUseOneRotationAndPersistTheNewPair()=runTest {
        val coordinator=SessionRefreshCoordinator(store)
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        var calls=0
        val first=async { coordinator.refresh("access0") { calls++; entered.complete(Unit); release.await(); RefreshOutcome.Success("access1","refresh1") } }
        entered.await()
        val second=async { coordinator.refresh("access0") { calls++; RefreshOutcome.Success("unexpected","unexpected") } }
        release.complete(Unit)
        assertEquals("access1",first.await()!!.accessToken)
        assertEquals("access1",second.await()!!.accessToken)
        assertEquals(1,calls)
        val restored=SecureStorageManager(context,prefs)
        assertEquals("access1" to "refresh1",restored.tokenPair())
        assertFalse(restored.replaceTokens("refresh0","stale","stale"))
        assertEquals("access1" to "refresh1",restored.tokenPair())
    }
    @Test fun logoutWaitsForRotationThenQueuesTheLatestGenerationAndCannotResurrect()=runTest {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val coordinator=SessionRefreshCoordinator(store)
        val refresh=async { coordinator.refresh("access0") { entered.complete(Unit); release.await(); RefreshOutcome.Success("access1","refresh1") } }
        entered.await()
        val logout=async { store.sessionMutex.withLock { store.endSession() } }
        release.complete(Unit); refresh.await(); logout.await()
        assertNull(store.tokenPair()); assertEquals(setOf("refresh1"),store.pendingRevocations())
        assertNull(coordinator.refresh("access1") { fail("Signed-out session refreshed"); RefreshOutcome.Unavailable })
        assertFalse(store.replaceTokens("refresh1","late","late"))
    }
    @Test fun temporaryFailureKeepsSessionButRejectionClearsAndDurablyQueuesLogout()=runTest {
        var scheduled=0
        val coordinator=SessionRefreshCoordinator(store) { scheduled++ }
        assertNull(coordinator.refresh("access0") { RefreshOutcome.Unavailable })
        assertEquals("access0" to "refresh0",store.tokenPair()); assertEquals(0,scheduled)
        assertNull(coordinator.refresh("access0") { RefreshOutcome.Rejected })
        assertNull(store.tokenPair()); assertEquals(1,scheduled)
        assertEquals(setOf("refresh0"),SecureStorageManager(context,prefs).pendingRevocations())
    }
    @Test fun offlineRevocationSurvivesRestartAndCannotClearANewerLogin()=runTest {
        store.endSession(); store.saveTokens("new-access","new-refresh")
        var fail=true
        val client=HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/auth/logout"))
            if(fail) respond("offline",HttpStatusCode.ServiceUnavailable) else respond("""{"success":true,"data":{"accepted":true},"error":null}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() }; install(HttpTimeout); defaultRequest { url(ApiConfig.BASE_URL); contentType(ContentType.Application.Json) } }
        try {
            assertFalse(SessionRevocations(context,store,client).flush())
            val restored=SecureStorageManager(context,prefs)
            assertEquals(setOf("refresh0"),restored.pendingRevocations())
            fail=false
            assertTrue(SessionRevocations(context,restored,client).flush())
            assertTrue(restored.pendingRevocations().isEmpty())
            assertEquals("new-access" to "new-refresh",restored.tokenPair())
            assertTrue(SessionRevocations(context,restored,client).flush())
        } finally { client.close() }
    }
    @Test fun actualSendGuardRejectsCachedTokensAfterLogoutAndStripsForeignSecrets()=runTest {
        val authorization=mutableListOf<String?>(); val shares=mutableListOf<String?>()
        val client=HttpClient(MockEngine { request ->
            authorization+=request.headers[HttpHeaders.Authorization]; shares+=request.headers["X-Vidoora-Share"]
            respond("ok")
        }) { install(Auth) { bearer { loadTokens { store.tokenPair()?.let { BearerTokens(it.first,it.second) } }; sendWithoutRequest { true } } } }
        SessionRequestGuard.install(client,store)
        try {
            client.get(ApiConfig.BASE_URL+"api/v1/users/me")
            store.endSession()
            client.get(ApiConfig.BASE_URL+"api/v1/users/me") // Plugin still holds its cached first pair.
            store.saveTokens("new-access","new-refresh")
            client.get(ApiConfig.BASE_URL+"api/v1/users/me") // Current identity replaces the old pair, without silently falling back to guest.
            client.plugin(Auth).providers.filterIsInstance<BearerAuthProvider>().forEach { it.clearToken() }
            client.get(ApiConfig.BASE_URL+"api/v1/users/me")
            client.get("https://foreign.example.test/media") { header("X-Vidoora-Share","private-capability") }
            client.post(ApiConfig.BASE_URL+"api/v1/auth/logout")
            assertEquals(listOf("Bearer access0",null,"Bearer new-access","Bearer new-access",null,null),authorization)
            assertTrue(shares.all { it==null })
        } finally { client.close() }
    }
    @Test fun repositoryLogoutCannotBeUndoneByALateLoginResponse()=runBlocking {
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,com.example.vidoora.core.database.AppDatabase::class.java).build()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine { request ->
            check(request.url.encodedPath.endsWith("/auth/login"))
            entered.complete(Unit); release.await()
            respond("""{"success":true,"data":{"accessToken":"access1","refreshToken":"refresh1","user":{"id":"creator","username":"creator","email":"creator@example.test","displayName":"Creator","createdAt":"2026-10-27"}},"error":null}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(Auth); install(ContentNegotiation) { json() }; defaultRequest { url(ApiConfig.BASE_URL); contentType(ContentType.Application.Json) } }
        try {
            val uploads=com.example.vidoora.core.data.repository.UploadRepositoryImpl(client,db.uploadTaskDao(),db.uploadPartDao(),db.userDao(),db,context)
            val repo=com.example.vidoora.core.data.repository.AuthRepositoryImpl(client,store,db.userDao(),uploads,
                com.example.vidoora.core.network.VideoShareAccess(),SessionRevocations(context,store,client))
            val login=async(Dispatchers.IO) { repo.login(com.example.vidoora.core.network.dto.LoginRequestDto("creator@example.test","Password123!")) }
            entered.await()
            val logout=async(Dispatchers.IO) { repo.logout() }
            release.complete(Unit)
            assertTrue(login.await() is com.example.vidoora.core.common.Resource.Success)
            logout.await()
            assertNull(store.tokenPair()); assertNull(db.userDao().getUserOnce())
            assertTrue(store.pendingRevocations().contains("refresh1"))
        } finally {
            androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
            client.close(); db.close()
        }
    }
    @Test fun refreshUsesTheActuallySentIdentityInsteadOfAStalePluginCache()=runTest {
        var rotations=0; var sends=0
        val coordinator=SessionRefreshCoordinator(store)
        val client=HttpClient(MockEngine { request ->
            sends++
            if(request.headers[HttpHeaders.Authorization]=="Bearer access1") respond("ok")
            else respond("expired",HttpStatusCode.Unauthorized,headers=headersOf(HttpHeaders.WWWAuthenticate,"Bearer"))
        }) {
            install(Auth) {
                bearer {
                    loadTokens { BearerTokens("cached-old","cached-old") }
                    sendWithoutRequest { true }
                    refreshTokens { coordinator.refresh(SessionRequestGuard.failedAccess(response)) {
                        rotations++; assertEquals("refresh0",it); RefreshOutcome.Success("access1","refresh1")
                    } }
                }
            }
        }
        SessionRequestGuard.install(client,store)
        try {
            assertEquals(HttpStatusCode.OK,client.get(ApiConfig.BASE_URL+"api/v1/users/me").status)
            assertEquals(1,rotations); assertEquals(2,sends)
        } finally { client.close() }
    }
}

