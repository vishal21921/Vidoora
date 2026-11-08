package com.vidoora.backend

import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.user.UsersTable
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

class CreatorAccountSecurityTest {
    private lateinit var db: Database
    private lateinit var auth: AuthService
    private var delivered: String?=null
    private val mailer=object: PasswordResetMailer {
        override val enabled=true
        override fun send(email: String,code: String): Boolean {delivered=code;return true}
    }
    private val jwt=JwtConfig(MapApplicationConfig("jwt.secret" to "creator-test-secret-at-least-32-characters"))
    @Before fun setup() {
        val previous=org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase
        db=Database.connect("jdbc:h2:mem:creator_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",driver="org.h2.Driver",user="sa",password="")
        org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase=previous
        transaction(db) {SchemaUtils.create(UsersTable,RefreshSessionsTable,CreatorInvites,PasswordResets)}
        auth=AuthService(jwt,db,mailer)
    }
    private fun digest(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun invitation(code: String,expiry: Instant=Instant.now().plusSeconds(600))=transaction(db) {
        CreatorInvites.insert {it[hash]=digest(code);it[expires]=expiry}
    }
    @Test fun blankNamesGenerateUniqueNumericIdsAndViewerAccess()=runBlocking {
        val first=auth.register(RegisterRequest(email="viewer@example.test",password="Password123!"))
        val second=auth.register(RegisterRequest(email="other@example.test",password="Password123!"))
        assertEquals("USER",first.user.role)
        assertEquals("10000001", first.user.publicId)
        assertEquals("10000002", second.user.publicId)
        assertTrue(first.user.publicId.length >= 8);assertTrue(first.user.publicId.all(Char::isDigit));assertNotEquals(first.user.publicId,second.user.publicId)
        assertEquals(first.user.publicId,auth.login(LoginRequest("viewer@example.test","Password123!")).user.publicId)
    }
    @Test fun invitationIsSingleUseAndFailedRegistrationRollsBack()=runBlocking {
        val code="test-invite-with-strong-randomness-123"
        invitation(code)
        val creator=auth.register(RegisterRequest(email="creator@example.test",password="Password123!",creatorInviteCode=code))
        assertEquals("CREATOR",creator.user.role)
        assertTrue(runCatching {auth.register(RegisterRequest(email="reuse@example.test",password="Password123!",creatorInviteCode=code))}.isFailure)
        assertEquals(1L,transaction(db) {UsersTable.selectAll().count()})
        assertEquals(digest(code),transaction(db) {CreatorInvites.selectAll().single()[CreatorInvites.hash]})
    }
    @Test fun expiredCodeFailsAndExistingViewerCanRedeem()=runBlocking {
        val viewer=auth.register(RegisterRequest(email="viewer@example.test",password="Password123!"))
        invitation("expired-invite-123456",Instant.now().minusSeconds(1))
        assertTrue(runCatching {auth.redeemCreatorInvite(viewer.user.id,"expired-invite-123456")}.isFailure)
        invitation("valid-invite-12345678")
        assertEquals("CREATOR",auth.redeemCreatorInvite(viewer.user.id,"valid-invite-12345678").role)
    }
    @Test fun resetConsumesHashOnceRevokesSessionsAndDoesNotRevealUnknownEmail()=runBlocking {
        val viewer=auth.register(RegisterRequest(email="viewer@example.test",password="Password123!"))
        auth.requestPasswordReset("absent@example.test");assertNull(delivered)
        assertEquals(0L,transaction(db) {PasswordResets.selectAll().count()})
        auth.requestPasswordReset("viewer@example.test")
        val code=checkNotNull(delivered)
        assertEquals(digest(code),transaction(db) {PasswordResets.selectAll().single()[PasswordResets.hash]})
        auth.confirmPasswordReset(code,"Replacement123!")
        assertTrue(runCatching {auth.confirmPasswordReset(code,"OtherPassword123!")}.isFailure)
        assertTrue(runCatching {auth.refreshToken(viewer.refreshToken)}.isFailure)
        assertTrue(runCatching {auth.login(LoginRequest("viewer@example.test","Password123!"))}.isFailure)
        assertEquals(viewer.user.id,auth.login(LoginRequest("viewer@example.test","Replacement123!")).user.id)
    }
}
