package com.vidoora.backend

import com.vidoora.backend.modules.membership.*
import kotlinx.coroutines.*
import org.flywaydb.core.Flyway
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID

class PlayPurchasePostgresIntegrationTest {
    @Test fun receiptClaimsAreDurableConcurrentAccountBoundAndEncrypted() = runBlocking {
        assumeTrue("Explicit PostgreSQL opt-in required", System.getenv("VIDOORA_POSTGRES_TEST") == "true")
        val url = checkNotNull(System.getenv("DATABASE_URL")); require(url.startsWith("jdbc:postgresql:"))
        val user = checkNotNull(System.getenv("DB_USERNAME")); val password = System.getenv("DB_PASSWORD") ?: ""
        val schema = "vidoora_billing_test_" + UUID.randomUUID().toString().replace("-", "")
        require(Regex("vidoora_billing_test_[0-9a-f]{32}").matches(schema))
        fun connection() = DriverManager.getConnection(url, user, password).also { c ->
            c.createStatement().use { it.execute("SET search_path TO $schema") }
        }
        DriverManager.getConnection(url, user, password).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate()
            val owner = UUID.randomUUID(); val other = UUID.randomUUID()
            connection().use { c ->
                for (account in listOf(owner, other)) c.prepareStatement("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (?,?,?,'fixture-only','Fixture')").use {
                    it.setObject(1, account); it.setString(2, account.toString().take(16)); it.setString(3, "$account@example.test"); it.executeUpdate()
                }
            }
            val key = ByteArray(32) { it.toByte() }; val token = "isolated-fixture-token"
            val ledger = PostgresPlayPurchaseLedger(PurchaseTokenCipher(key), ::connection)
            val results = (1..6).map { async(Dispatchers.IO) { ledger.claim(owner, token) } }.awaitAll()
            assertEquals(1, results.distinct().size)
            val restored = PostgresPlayPurchaseLedger(PurchaseTokenCipher(key), ::connection)
            assertEquals(results.first(), restored.claim(owner, token)); assertTrue(restored.tokens(owner).isEmpty())
            val now=java.time.Instant.now()
            restored.record(owner,token,PlayReceipt(PlayPlan("viewer","monthly","MONTHLY"),owner,"fixture-period",now.minusSeconds(1),now.plusSeconds(3600),true,true,false,purchaseDigest(token)),"ACKNOWLEDGED")
            assertEquals(listOf(token),restored.tokens(owner))
            assertTrue(restored.tokens(other).isEmpty())
            try { restored.claim(other, token); fail() } catch (e: PlayVerificationFailure) { assertEquals("PURCHASE_ACCOUNT_MISMATCH", e.code) }
            connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT token_hash,encrypted_token FROM play_purchases").use { r ->
                assertTrue(r.next()); assertEquals(purchaseDigest(token), r.getString(1)); assertFalse(r.getString(2).contains(token)); assertFalse(r.next())
            } } }
        } finally {
            DriverManager.getConnection(url, user, password).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
