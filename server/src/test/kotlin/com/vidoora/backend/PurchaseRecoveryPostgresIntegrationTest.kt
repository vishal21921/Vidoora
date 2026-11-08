package com.vidoora.backend

import com.vidoora.backend.modules.membership.*
import kotlinx.coroutines.*
import org.flywaydb.core.Flyway
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class PurchaseRecoveryPostgresIntegrationTest {
    @Test fun migrationsClaimsHistoryChainsAndLeaseFencesUseRealPostgres()=runBlocking {
        assumeTrue(System.getenv("VIDOORA_POSTGRES_TEST")=="true")
        val url=checkNotNull(System.getenv("DATABASE_URL"));require(url.startsWith("jdbc:postgresql:"))
        val user=checkNotNull(System.getenv("DB_USERNAME"));val password=System.getenv("DB_PASSWORD") ?: ""
        val schema="vidoora_recovery_test_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("vidoora_recovery_test_[0-9a-f]{32}").matches(schema))
        fun connection()=DriverManager.getConnection(url,user,password).also{db->db.createStatement().use{it.execute("SET search_path TO $schema")}}
        DriverManager.getConnection(url,user,password).use{db->db.createStatement().use{it.execute("CREATE SCHEMA $schema")}}
        try {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("11").load().migrate()
            val owner=UUID.randomUUID();val other=UUID.randomUUID();val now=Instant.now();val cipher=PurchaseTokenCipher(ByteArray(32){it.toByte()})
            connection().use {db->for(id in listOf(owner,other)) db.prepareStatement("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (?,?,?,'fixture','Fixture')").use{s->
                s.setObject(1,id);s.setString(2,id.toString().take(16));s.setString(3,"$id@example.test");s.executeUpdate()
            }}
            val legacy="legacy-fixture-token"
            connection().use {db->db.prepareStatement("INSERT INTO play_purchases(token_hash,user_id,encrypted_token,support_id,state,updated_at) VALUES (?,?,?,?,'ACKNOWLEDGED',CURRENT_TIMESTAMP)").use{s->
                s.setString(1,purchaseDigest(legacy));s.setObject(2,owner);s.setString(3,cipher.encrypt(legacy));s.setObject(4,UUID.randomUUID());s.executeUpdate()
            }}
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
            val ledger=PostgresPlayPurchaseLedger(cipher,::connection)
            assertEquals(listOf(legacy),ledger.tokens(owner)) // V11 ownership is backfilled by V12.
            try {ledger.claimLinked(other,"foreign-new",purchaseDigest(legacy));fail()} catch(e: PlayVerificationFailure){assertEquals("PURCHASE_ACCOUNT_MISMATCH",e.code)}
            ledger.claimLinked(owner,"replacement",purchaseDigest(legacy));assertTrue(ledger.tokens(owner).isEmpty())
            try {ledger.claim(owner,legacy);fail()} catch(e: PlayVerificationFailure){assertEquals("PURCHASE_SUPERSEDED",e.code)}
            val store=PostgresPurchaseRecovery(cipher,::connection);val token="queue-fixture-token"
            val received=store.enqueue(owner,token,now)
            assertEquals(received.reference,store.enqueue(owner,token,now).reference)
            val foreign=store.enqueue(other,token,now);assertNotEquals(received.reference,foreign.reference)
            val claims=(1..4).map{async(Dispatchers.IO){store.claim(now)}}.awaitAll().filterNotNull()
            assertEquals(2,claims.size);assertEquals(2,claims.map{it.id}.distinct().size)
            val own=claims.single{it.account==owner};val wrong=claims.single{it.account==other}
            fun receipt(order: String,rawToken: String=token)=PlayReceipt(PlayPlan("viewer","monthly","MONTHLY"),owner,order,now.minusSeconds(1),now.plusSeconds(3600),true,true,false,purchaseDigest(rawToken))
            assertTrue(store.finish(own,RecoveryResult("SUCCESS","ACTIVE",receipt("fixture-order-1"),next=now.plusSeconds(900)),now))
            assertFalse(store.finish(own,RecoveryResult("UNKNOWN","RETRYING"),now))
            assertTrue(store.finish(wrong,RecoveryResult("UNKNOWN","REJECTED"),now))
            assertEquals("SUCCESS",store.page(owner,null).items.single().paymentState)
            assertEquals(received.reference,store.page(owner,null).items.single().reference)
            assertNull(store.page(other,null).items.single().planId)
            try {store.retry(other,UUID.fromString(received.reference),now.plusSeconds(60));fail()} catch(_: PaymentNotFound){}
            store.enqueue(owner,token,now.plusSeconds(60))
            val renewal=checkNotNull(store.claim(now.plusSeconds(60)))
            assertTrue(store.finish(renewal,RecoveryResult("SUCCESS","ACTIVE",receipt("fixture-order-2"),next=now.plusSeconds(960)),now.plusSeconds(60)))
            val history=store.page(owner,null).items
            assertEquals(2,history.size);assertEquals(2,history.map{it.reference}.distinct().size)
            val alias=store.enqueue(owner,"same-order-alias",now.plusSeconds(61))
            val aliasClaim=checkNotNull(store.claim(now.plusSeconds(61)))
            assertEquals(alias.reference,aliasClaim.id.toString())
            assertTrue(store.finish(aliasClaim,RecoveryResult("SUCCESS","ACTIVE",receipt("fixture-order-2","same-order-alias")),now.plusSeconds(61)))
            assertEquals(2,store.page(owner,null).items.size) // One observed order, even if Google delivers a different token.
            try {store.page(other,UUID.fromString(history.first().reference));fail()} catch(_: PaymentNotFound){}
            val newJob=store.enqueue(owner,"crash-fixture",now.plusSeconds(62))
            val stale=checkNotNull(store.claim(now.plusSeconds(62)))
            val reclaimed=checkNotNull(store.claim(now.plusSeconds(363)))
            assertEquals(stale.id,reclaimed.id);assertNotEquals(stale.lease,reclaimed.lease)
            assertFalse(store.finish(stale,RecoveryResult("UNKNOWN","RETRYING"),now.plusSeconds(363)))
            assertTrue(store.finish(reclaimed,RecoveryResult("PENDING","PENDING",next=now.plusSeconds(1000)),now.plusSeconds(363)))
            assertTrue(store.page(owner,null).items.any{it.reference==newJob.reference && it.paymentState=="PENDING"})
        } finally {DriverManager.getConnection(url,user,password).use{db->db.createStatement().use{it.execute("DROP SCHEMA $schema CASCADE")}}}
    }
}
