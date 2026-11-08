package com.example.vidoora

import android.content.Context
import com.example.vidoora.feature.ads.ViewerAdsController
import com.example.vidoora.feature.membership.ViewerMembershipRepository
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking

/** Only native SDK tests: a synthetic guest response, never a production paid-access shortcut. */
internal fun freeAdsController(context: Context): ViewerAdsController {
    val client=HttpClient(MockEngine { respond("""{"success":true,"data":{"validForSeconds":30}}""",
        headers=headersOf(HttpHeaders.ContentType,"application/json")) }) {
        defaultRequest { url("https://fixture.test/") };install(io.ktor.client.plugins.HttpTimeout)
        install(ContentNegotiation) { json() }
    }
    val membership=ViewerMembershipRepository(client,{ "guest" },android.os.SystemClock::elapsedRealtime)
    runBlocking { membership.refresh() }
    client.close()
    return ViewerAdsController(context,membership)
}
