package com.vidoora.backend

import com.vidoora.backend.modules.auth.*
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID

class AuthSessionPostgresIntegrationTest {
    @Test fun postgresRotationRaceLogoutFencingAndPersistence()=runBlocking {
        assumeTrue("Explicit real PostgreSQL opt-in required",System.getenv("VIDOORA_POSTGRES_TEST")=="true")
        val url=checkNotNull(System.getenv("DATABASE_URL")); require(url.startsWith("jdbc:postgresql:"))
        val username=checkNotNull(System.getenv("DB_USERNAME")); val password=checkNotNull(System.getenv("DB_PASSWORD"))
        val schema="vidoora_session_test_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("vidoora_session_test_[0-9a-f]{32}").matches(schema))
        DriverManager.getConnection(url,username,password).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val previous=TransactionManager.defaultDatabase
        try {
            Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
            val db=Database.connect(url+(if(url.contains('?')) "&" else "?")+"currentSchema=$schema",driver="org.postgresql.Driver",user=username,password=password)
            TransactionManager.defaultDatabase=previous
            val jwt=JwtConfig(MapApplicationConfig("jwt.secret" to "postgres-session-test-secret-at-least-32-characters"))
            val auth=AuthService(jwt,db)
            val first=auth.register(RegisterRequest("fixture","fixture@example.test","Password123!","Fixture"))
            val independent=auth.login(LoginRequest("fixture@example.test","Password123!"))
            val results=(1..8).map { async(Dispatchers.IO) { runCatching { auth.refreshToken(first.refreshToken) } } }.awaitAll()
            assertEquals(1,results.count { it.isSuccess })
            assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is InvalidRefreshToken })
            val winner=results.single { it.isSuccess }.getOrThrow()
            // New service instance reads persisted session state, without an in-memory allowlist.
            val restored=AuthService(jwt,db).refreshToken(winner.refreshToken)
            val logout=async(Dispatchers.IO) { auth.logout(first.refreshToken) }
            val racing=async(Dispatchers.IO) { runCatching { auth.refreshToken(restored.refreshToken) } }
            logout.await(); val rotated=racing.await().getOrNull()
            for(token in listOfNotNull(first.refreshToken,winner.refreshToken,restored.refreshToken,rotated?.refreshToken)) {
                try { auth.refreshToken(token); fail("Signed-out session renewed") } catch (_: InvalidRefreshToken) { }
            }
            assertEquals(first.user.id,auth.refreshToken(independent.refreshToken).user.id)
            val owner=UUID.fromString(first.user.id)
            val session=UUID.fromString(jwt.verifier.verify(first.refreshToken).getClaim("sid").asString())
            DriverManager.getConnection(url,username,password).use { c ->
                c.prepareStatement("SELECT token_hash, revoked_at FROM $schema.refresh_sessions WHERE id=? AND user_id=?").use { s ->
                    s.setObject(1,session); s.setObject(2,owner)
                    s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(64,r.getString(1).length); assertNotNull(r.getTimestamp(2)) }
                }
            }
        } finally {
            TransactionManager.defaultDatabase=previous
            DriverManager.getConnection(url,username,password).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
