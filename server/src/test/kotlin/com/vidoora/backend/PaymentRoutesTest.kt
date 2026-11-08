package com.vidoora.backend

import com.vidoora.backend.infrastructure.*
import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.membership.*
import com.vidoora.backend.plugins.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PaymentRoutesTest {
    private val config=MapApplicationConfig("jwt.secret" to "fixture-secret-at-least-32-characters-long",
        "database.jdbcUrl" to "jdbc:h2:mem:vidoora_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL","database.driverClassName" to "org.h2.Driver",
        "database.username" to "sa","database.password" to "","database.allowInMemoryFallback" to "true","r2.mockMode" to "true")
    private class Store: PurchaseRecoveryStore {
        val owners=mutableMapOf<UUID,PaymentRecord>();var submissions=0
        override suspend fun enqueue(account: UUID,token: String,now: Instant): PaymentRecord {
            submissions++;return owners.getOrPut(account){PaymentRecord(UUID.randomUUID().toString(),"UNKNOWN","RECEIVED",createdAt=now.toString(),updatedAt=now.toString())}
        }
        override suspend fun page(account: UUID,cursor: UUID?)=PaymentPage(true,listOfNotNull(owners[account]))
        override suspend fun retry(account: UUID,reference: UUID,now: Instant)=owners[account]?.takeIf{it.reference==reference.toString()} ?: throw PaymentNotFound()
        override suspend fun claim(now: Instant): RecoveryClaim?=null
        override suspend fun finish(claim: RecoveryClaim,result: RecoveryResult,now: Instant)=false
    }
    @Test fun authenticationStrictInputAndAccountScopedReferences()=testApplication {
        environment{config=MapApplicationConfig()}
        DatabaseFactory.init(config);val jwt=JwtConfig(config);val auth=AuthService(jwt)
        suspend fun register(): String {val id=UUID.randomUUID().toString().take(8);return auth.register(RegisterRequest("r_$id","$id@example.test","Password123!","Fixture")).user.id}
        val owner=register();val other=register();val store=Store();val plans=listOf(PlayPlan("viewer","monthly","MONTHLY"))
        val publisher=object: PlayPublisher {
            override suspend fun subscription(token: String): kotlinx.serialization.json.JsonObject=error("HTTP submission must not call Google")
            override suspend fun acknowledge(productId: String,token: String)=error("Must not acknowledge here")
        }
        val ledger=object: PlayPurchaseLedger {
            override suspend fun claim(account: UUID,token: String): UUID=error("Not before verification")
            override suspend fun record(account: UUID,token: String,receipt: PlayReceipt?,state: String){}
            override suspend fun tokens(account: UUID)=emptyList<String>()
        }
        val recovery=PurchaseRecovery(store,PlayPurchaseVerifier(publisher,plans),ledger,VerifiedViewerEntitlements(VerifiedGrantSource{emptyList()}),plans)
        application {configureSerialization();configureSecurity(jwt);configureStatusPages();routing{paymentRoutes(auth,recovery)}}
        val path="/api/v1/payments"
        fun token(account: String)=jwt.generateAccessToken(account,"fixture@example.test","USER")
        assertEquals(HttpStatusCode.Unauthorized,client.get(path).status)
        val accepted=client.post("$path/receipts"){bearerAuth(token(owner));contentType(ContentType.Application.Json);setBody("""{"token":"fixture-private-token"}""")}
        assertEquals(HttpStatusCode.Accepted,accepted.status);assertEquals("no-store",accepted.headers[HttpHeaders.CacheControl]);assertFalse(accepted.bodyAsText().contains("fixture-private-token"))
        val ref=store.owners[UUID.fromString(owner)]!!.reference
        assertTrue(client.get(path){bearerAuth(token(owner))}.bodyAsText().contains(ref))
        assertFalse(client.get(path){bearerAuth(token(other))}.bodyAsText().contains(ref))
        assertEquals(HttpStatusCode.NotFound,client.post("$path/$ref/retry"){bearerAuth(token(other))}.status)
        for(body in listOf("""{"token":"fixture-private-token","paid":true}""","""{"token":"bad token"}""","x".repeat(8193))) {
            val response=client.post("$path/receipts"){bearerAuth(token(owner));contentType(ContentType.Application.Json);setBody(body)}
            assertEquals(HttpStatusCode.BadRequest,response.status);assertFalse(response.bodyAsText().contains("fixture-private-token"))
        }
        assertEquals(1,store.submissions)
        assertTrue(client.get("$path/config"){bearerAuth(token(owner))}.bodyAsText().replace(" ","").contains("\"purchasesAvailable\":false"))
    }
    @Test fun disabledRecoveryIsExplicitAndCannotAcceptReceipts()=testApplication {
        environment{config=MapApplicationConfig()};DatabaseFactory.init(config)
        val jwt=JwtConfig(config);val auth=AuthService(jwt);val id=UUID.randomUUID().toString().take(8)
        val user=auth.register(RegisterRequest("d_$id","$id@example.test","Password123!","Fixture")).user.id
        application {configureSerialization();configureSecurity(jwt);configureStatusPages();routing{paymentRoutes(auth,null)}}
        val token=jwt.generateAccessToken(user,"fixture@example.test","USER")
        assertTrue(client.get("/api/v1/payments"){bearerAuth(token)}.bodyAsText().replace(" ","").contains("\"available\":false"))
        assertEquals(HttpStatusCode.ServiceUnavailable,client.post("/api/v1/payments/receipts"){bearerAuth(token);setBody("private-not-processed")}.status)
    }
}
