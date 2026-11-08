package com.example.vidoora.feature.membership

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vidoora.core.security.SecureStorageManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReceiptOutboxTest {
    private val account=UUID.randomUUID().toString()
    private fun storage(): SecureStorageManager {
        val context=ApplicationProvider.getApplicationContext<Context>()
        return SecureStorageManager(context,context.getSharedPreferences("receipt-fixture-${UUID.randomUUID()}",Context.MODE_PRIVATE)).also{login(it,account)}
    }
    private fun login(storage: SecureStorageManager,account: String) {
        val payload=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$account"}""".toByteArray())
        storage.saveTokens("fixture.$payload.signature","fixture-refresh")
    }
    private val accepted="""{"success":true,"data":{"reference":"00000000-0000-0000-0000-000000000001","paymentState":"UNKNOWN","activationState":"RECEIVED","createdAt":"2026-10-30T00:00:00Z","updatedAt":"2026-10-30T00:00:00Z"}}"""
    @Test fun unacceptedReceiptsSurviveNewInstancesAndOnlyAcceptedOnesAreRemoved()=runBlocking {
        val storage=storage();var ready=false;var requests=0;var schedules=0
        val client=HttpClient(MockEngine {
            requests++
            respond(if(ready) accepted else "unavailable",if(ready) HttpStatusCode.Accepted else HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.ContentType,"application/json"))
        }) {defaultRequest{url("https://fixture.test/");contentType(ContentType.Application.Json)};install(HttpTimeout);install(ContentNegotiation){json()}}
        try {
            ReceiptOutbox(storage,client,{schedules++},{}).accept(account,"fixture-receipt")
            assertEquals(1,schedules);assertEquals(0,requests)
            val restored=ReceiptOutbox(storage,client,{},{});assertFalse(restored.flush())
            assertTrue(storage.readReceiptOutbox()!!.contains("fixture-receipt")) // Supplied test preferences only, not production encryption.
            ready=true;assertTrue(restored.flush());assertFalse(storage.readReceiptOutbox()!!.contains("fixture-receipt"));assertEquals(2,requests)
        } finally {client.close()}
    }
    @Test fun logoutAndForeignAccountNeverDrainAnotherAccountsReceipts()=runBlocking {
        val storage=storage();var requests=0
        val client=HttpClient(MockEngine {requests++;respond(accepted,headers=headersOf(HttpHeaders.ContentType,"application/json"))}) {
            defaultRequest{url("https://fixture.test/");contentType(ContentType.Application.Json)};install(HttpTimeout);install(ContentNegotiation){json()}
        }
        try {
            val outbox=ReceiptOutbox(storage,client,{},{});outbox.accept(account,"fixture-receipt")
            storage.clearTokens();assertTrue(outbox.flush());assertEquals(0,requests)
            login(storage,UUID.randomUUID().toString());assertTrue(outbox.flush());assertEquals(0,requests)
            login(storage,account);assertTrue(outbox.flush());assertEquals(1,requests)
        } finally {client.close()}
    }
    @Test fun switchingIdentityDuringHttpAcceptanceKeepsReceiptForTheOriginalAccount()=runBlocking {
        val storage=storage();var requests=0
        val client=HttpClient(MockEngine {
            requests++;login(storage,UUID.randomUUID().toString())
            respond(accepted,headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) {defaultRequest{url("https://fixture.test/");contentType(ContentType.Application.Json)};install(HttpTimeout);install(ContentNegotiation){json()}}
        try {
            val outbox=ReceiptOutbox(storage,client,{},{});outbox.accept(account,"fixture-receipt")
            assertTrue(outbox.flush());assertTrue(storage.readReceiptOutbox()!!.contains("fixture-receipt"))
            assertTrue(outbox.flush());assertEquals(1,requests)
        } finally {client.close()}
    }
    @Test fun duplicateDeliveryIsIdempotentAndObjectsAreRedacted()=runBlocking {
        val storage=storage();var requests=0
        val client=HttpClient(MockEngine {requests++;respond(accepted,headers=headersOf(HttpHeaders.ContentType,"application/json"))}) {
            defaultRequest{url("https://fixture.test/");contentType(ContentType.Application.Json)};install(HttpTimeout);install(ContentNegotiation){json()}
        }
        try {
            val outbox=ReceiptOutbox(storage,client,{},{});repeat(3){outbox.accept(account,"fixture-receipt")}
            assertTrue(outbox.flush());assertEquals(1,requests)
            assertFalse(ReceiptBody("fixture-receipt").toString().contains("fixture-receipt"))
            assertFalse(PendingReceipt(account,"fixture-receipt").toString().contains("fixture-receipt"))
        } finally {client.close()}
    }
}
