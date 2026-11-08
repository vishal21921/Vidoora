package com.vidoora.backend.modules.membership

import com.vidoora.backend.common.ApiResponse
import com.vidoora.backend.modules.auth.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

private fun paymentId(value: String): UUID=UUID.fromString(value).also { require(it.toString()==value.lowercase()) }
fun Route.paymentRoutes(auth: AuthService,recovery: PurchaseRecovery?) {
    authenticate("auth-jwt") {
        route("/api/v1/payments") {
            intercept(ApplicationCallPipeline.Call) { call.response.header(HttpHeaders.CacheControl,"no-store") }
            suspend fun ApplicationCall.account(): UUID {
                val profile=auth.getUserProfile(principal<JWTPrincipal>()!!.subject!!)
                if(profile.isSuspended) throw PlayVerificationFailure("ACCOUNT_UNAVAILABLE")
                return UUID.fromString(profile.id)
            }
            suspend fun ApplicationCall.action(block: suspend ()->Unit) {
                try { block() }
                catch(e: CancellationException) { throw e }
                catch (_: PaymentNotFound) { respond(HttpStatusCode.NotFound,ApiResponse.error<Unit>("NOT_FOUND","Payment unavailable")) }
                catch (_: PaymentRateLimited) { respond(HttpStatusCode.TooManyRequests,ApiResponse.error<Unit>("TRY_LATER","Please try again later")) }
                catch (_: SerializationException) { respond(HttpStatusCode.BadRequest,ApiResponse.error<Unit>("INVALID_REQUEST","Invalid payment request")) }
                catch (_: IllegalArgumentException) { respond(HttpStatusCode.BadRequest,ApiResponse.error<Unit>("INVALID_REQUEST","Invalid payment request")) }
                catch (e: PlayVerificationFailure) {
                    val status=if(e.code=="ACCOUNT_UNAVAILABLE") HttpStatusCode.Forbidden else HttpStatusCode.BadRequest
                    respond(status,ApiResponse.error<Unit>("VERIFICATION_UNAVAILABLE","Purchase could not be verified"))
                }
            }
            get("/config") { call.action { call.account();call.respond(ApiResponse.success(recovery?.config() ?: BillingRecoveryConfig())) } }
            get {
                call.action {
                    val account=call.account()
                    val cursor=call.request.queryParameters["cursor"]?.let(::paymentId)
                    call.respond(ApiResponse.success(recovery?.page(account,cursor) ?: PaymentPage(false,emptyList())))
                }
            }
            post("/receipts") {
                call.action {
                    val account=call.account()
                    val service=recovery ?: return@action call.respond(HttpStatusCode.ServiceUnavailable,ApiResponse.error<Unit>("NOT_READY","Purchase recovery is not configured"))
                    val input=call.receiveChannel();val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(2048)
                    try {
                        while(true) { val n=input.readAvailable(buffer,0,buffer.size);if(n<0) break;require(bytes.size()+n<=8192);bytes.write(buffer,0,n) }
                    } finally { input.cancel(null) }
                    val request=Json.decodeFromString<SubmitPurchase>(bytes.toString(Charsets.UTF_8))
                    call.respond(HttpStatusCode.Accepted,ApiResponse.success(service.submit(account,request.token)))
                }
            }
            post("/{reference}/retry") {
                call.action {
                    val account=call.account()
                    val service=recovery ?: return@action call.respond(HttpStatusCode.ServiceUnavailable,ApiResponse.error<Unit>("NOT_READY","Purchase recovery is not configured"))
                    call.respond(HttpStatusCode.Accepted,ApiResponse.success(service.retry(account,paymentId(call.parameters["reference"] ?: ""))))
                }
            }
        }
    }
}
