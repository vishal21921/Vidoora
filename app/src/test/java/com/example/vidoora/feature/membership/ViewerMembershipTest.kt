package com.example.vidoora.feature.membership

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ViewerMembershipTest {
    private val account="00000000-0000-0000-0000-000000000001"
    private fun paid(ttl: Long=60)=MembershipResponse(account,"MONTHLY",true,null,"2026-11-30T00:00:00Z",ttl,30)
    @Test fun adminResponseIsAccountBoundAndRequiresUnlimitedAdFreeBenefits() {
        val response=paid(30).copy(planId="ADMIN",downloadsPerPeriod=Int.MAX_VALUE)
        val lease=checkNotNull(MembershipLease.accept("user:$account",response,100))
        assertTrue(lease.valid("user:$account",101))
        assertFalse(lease.valid("guest",101))
        assertNull(MembershipLease.accept("user:$account",response.copy(adFree=false),100))
        assertNull(MembershipLease.accept("user:$account",response.copy(downloadsPerPeriod=30),100))
    }
    @Test fun leaseIsBoundToAccountMonotonicTimeAndSixtySecondMaximum() {
        val lease=MembershipLease.accept("user:$account",paid(),1_000)!!
        assertTrue(lease.valid("user:$account",60_999));assertFalse(lease.valid("user:$account",61_000))
        assertFalse(lease.valid("guest",2_000));assertFalse(lease.valid("user:$account",999))
        assertNull(MembershipLease.accept("guest",paid(),1_000))
        assertNull(MembershipLease.accept("user:$account",paid(61),1_000))
        assertNull(MembershipLease.accept("user:$account",paid(0),1_000))
    }
    @Test fun contradictoryOrUnknownBenefitsAreRejected() {
        for(response in listOf(paid().copy(planId="UNKNOWN"),paid().copy(adFree=false),paid().copy(maxHeight=480),
            paid().copy(expiresAt=null),paid().copy(downloadsPerPeriod=1000),MembershipResponse(accountId=account,adFree=true,validForSeconds=30))) {
            assertNull(MembershipLease.accept("user:$account",response,0))
        }
    }
    @Test fun refreshAccountSwitchDuringRequestCannotInstallForeignPaidBenefits()= runBlocking {
        var who="user:$account";var elapsed=100L
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine {
            entered.complete(Unit);release.await()
            respond("""{"success":true,"data":{"accountId":"$account","planId":"MONTHLY","adFree":true,"maxHeight":null,"expiresAt":"2026-11-30T00:00:00Z","validForSeconds":60,"downloadsPerPeriod":30}}""",
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) {
            defaultRequest { url("https://fixture.test/") };install(io.ktor.client.plugins.HttpTimeout)
            install(ContentNegotiation) { json() }
        }
        try {
            val repository=ViewerMembershipRepository(client,{ who },{ elapsed })
            val work=async { repository.refresh() };kotlinx.coroutines.withTimeout(10_000) { entered.await() };who="guest";release.complete(Unit);work.await()
            assertFalse(repository.current().adFree);assertEquals(480,repository.current().maxHeight)
        } finally { client.close() }
    }
    @Test fun leaseIncludesNetworkDelayAndIsNotPersistedAcrossRepositoryInstances()= runBlocking {
        var elapsed=1_000L
        val client=HttpClient(MockEngine {
            elapsed+=5_000
            respond("""{"success":true,"data":{"accountId":"$account","planId":"MONTHLY","adFree":true,"maxHeight":null,"expiresAt":"2026-11-30T00:00:00Z","validForSeconds":10,"downloadsPerPeriod":30}}""",
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) {
            defaultRequest { url("https://fixture.test/") };install(io.ktor.client.plugins.HttpTimeout)
            install(ContentNegotiation) { json() }
        }
        try {
            val repository=ViewerMembershipRepository(client,{ "user:$account" },{ elapsed })
            repository.refresh();assertTrue(repository.current().adFree)
            elapsed=11_000;repository.tick();assertFalse(repository.state.value.adFree);assertEquals(480,repository.state.value.maxHeight)
            assertFalse(ViewerMembershipRepository(client,{ "user:$account" },{ elapsed }).current().adFree)
        } finally { client.close() }
    }
    @Test fun rejectedOrUnavailableStatusNeverGrantsPaidAccess()= runBlocking {
        val client=HttpClient(MockEngine { respond("unavailable",HttpStatusCode.ServiceUnavailable) }) {
            defaultRequest { url("https://fixture.test/") };install(io.ktor.client.plugins.HttpTimeout)
        }
        try {
            val repository=ViewerMembershipRepository(client,{ "guest" },{ 1_000 })
            repository.refresh();assertFalse(repository.current().adFree);assertFalse(repository.current().loading)
            assertEquals(480,repository.current().maxHeight)
        } finally { client.close() }
    }
}
