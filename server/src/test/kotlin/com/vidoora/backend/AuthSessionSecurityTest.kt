package com.vidoora.backend

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.user.UsersTable
import com.vidoora.backend.plugins.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AuthSessionSecurityTest {
    private val config=MapApplicationConfig("jwt.secret" to "session-fixture-secret-at-least-32-characters")
    private val jwt=JwtConfig(config)
    private lateinit var db: Database
    private lateinit var auth: AuthService
    private lateinit var first: TokenResponse
    @Before fun setup()=runBlocking {
        val previous=TransactionManager.defaultDatabase
        db=Database.connect("jdbc:h2:mem:auth_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",driver="org.h2.Driver",user="sa",password="")
        transaction(db) { SchemaUtils.create(UsersTable,RefreshSessionsTable) }
        TransactionManager.defaultDatabase=previous
        auth=AuthService(jwt,db)
        first=auth.register(RegisterRequest("fixture","fixture@example.test","Password123!","Fixture"))
    }
    private suspend fun invalid(token: String) {
        try { auth.refreshToken(token); fail("Refresh unexpectedly accepted") } catch (_: InvalidRefreshToken) { }
    }
    @Test fun rotationsAreUniqueSingleUseHashOnlyAndHaveAbsoluteExpiry()=runBlocking {
        val initial=jwt.verifier.verify(first.refreshToken)
        val second=auth.refreshToken(first.refreshToken)
        val third=auth.refreshToken(second.refreshToken)
        assertNotEquals(first.refreshToken,second.refreshToken); assertNotEquals(second.refreshToken,third.refreshToken)
        assertNotEquals(second.accessToken,third.accessToken)
        assertEquals(initial.expiresAt,jwt.verifier.verify(third.refreshToken).expiresAt)
        invalid(first.refreshToken); invalid(second.refreshToken)
        newSuspendedTransaction(db=db) {
            val row=RefreshSessionsTable.selectAll().single()
            assertEquals(64,row[RefreshSessionsTable.tokenHash].length)
            assertFalse(row[RefreshSessionsTable.tokenHash].contains(third.refreshToken))
            assertNotNull(row[RefreshSessionsTable.lastUsedAt])
        }
        assertEquals(first.user.id,auth.refreshToken(third.refreshToken).user.id)
    }
    @Test fun concurrentRefreshHasExactlyOneWinner()=runBlocking {
        val results=(1..6).map { async(Dispatchers.IO) { runCatching { auth.refreshToken(first.refreshToken) } } }.awaitAll()
        assertEquals(1,results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is InvalidRefreshToken })
        val winner=results.single { it.isSuccess }.getOrThrow()
        assertEquals(first.user.id,auth.refreshToken(winner.refreshToken).user.id)
    }
    @Test fun logoutRevokesRotatedFamilyAndPreservesOtherLoginsAndAccounts()=runBlocking {
        val otherLogin=auth.login(LoginRequest("fixture@example.test","Password123!"))
        val otherUser=auth.register(RegisterRequest("second","second@example.test","Password123!","Second"))
        val rotated=auth.refreshToken(first.refreshToken)
        auth.logout(first.refreshToken); auth.logout(first.refreshToken); auth.logout("invalid")
        invalid(first.refreshToken); invalid(rotated.refreshToken)
        assertEquals(first.user.id,auth.refreshToken(otherLogin.refreshToken).user.id)
        assertEquals(otherUser.user.id,auth.refreshToken(otherUser.refreshToken).user.id)
    }
    @Test fun expiredMissingLegacyWrongTypeWrongSignatureAndSuspendedSessionsFail()=runBlocking {
        invalid(""); invalid("bad"); invalid("x".repeat(4097)); invalid(first.accessToken)
        invalid(jwt.generateRefreshToken(first.user.id))
        val alien=JwtConfig(MapApplicationConfig("jwt.secret" to "different-session-fixture-secret-at-least-32"))
        invalid(alien.generateSessionRefreshToken(first.user.id,UUID.randomUUID(),Instant.now().plusSeconds(500)))
        invalid(jwt.generateSessionRefreshToken(first.user.id,UUID.randomUUID(),Instant.now().minusSeconds(5)))
        newSuspendedTransaction(db=db) { UsersTable.update({ UsersTable.id eq UUID.fromString(first.user.id) }) { it[isSuspended]=true } }
        invalid(first.refreshToken)
        newSuspendedTransaction(db=db) {
            UsersTable.update({ UsersTable.id eq UUID.fromString(first.user.id) }) { it[isSuspended]=false }
            RefreshSessionsTable.update({ RefreshSessionsTable.userId eq UUID.fromString(first.user.id) }) { it[expiresAt]=Instant.now().minusSeconds(1) }
        }
        invalid(first.refreshToken)
    }
    @Test fun refreshAndLogoutRoutesAreNoStoreAndCapabilitiesCannotRenew()=testApplication {
        environment { config=MapApplicationConfig() }
        application { configureSerialization(); configureSecurity(jwt); configureStatusPages(); routing { authRoutes(auth) } }
        val response=client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${first.refreshToken}"}""") }
        assertEquals(HttpStatusCode.OK,response.status); assertEquals("no-store",response.headers[HttpHeaders.CacheControl])
        val replay=client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${first.refreshToken}"}""") }
        assertEquals(HttpStatusCode.Unauthorized,replay.status)
        assertEquals("no-store",replay.headers[HttpHeaders.CacheControl])
        val logout=client.post("/api/v1/auth/logout") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${first.refreshToken}"}""") }
        assertEquals(HttpStatusCode.OK,logout.status); assertEquals("no-store",logout.headers[HttpHeaders.CacheControl])
        val bad=client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${first.accessToken}"}""") }
        assertEquals(HttpStatusCode.Unauthorized,bad.status)
    }
}
