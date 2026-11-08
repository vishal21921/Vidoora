package com.vidoora.backend.modules.downloads

import com.vidoora.backend.common.ApiResponse
import com.vidoora.backend.modules.playback.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

fun Route.downloadRoutes(service: PrivateDownloads) {
    authenticate("auth-jwt") {route("/api/v1/downloads") {
        suspend fun ApplicationCall.action(block: suspend (String,String?)->Unit) {
            response.header(HttpHeaders.CacheControl,"no-store")
            try {block(principal<JWTPrincipal>()!!.subject!!,request.headers["X-Vidoora-Share"])}
            catch(e: CancellationException){throw e}
            catch(_: DownloadQuotaReached){respond(HttpStatusCode.Conflict,ApiResponse.error<Unit>("DOWNLOAD_LIMIT","Your plan download allowance is used"))}
            catch(_: DownloadIncomplete){respond(HttpStatusCode.Conflict,ApiResponse.error<Unit>("INCOMPLETE","Download transfer is not complete; retry"))}
            catch(_: DownloadDenied){respond(HttpStatusCode.Forbidden,ApiResponse.error<Unit>("DOWNLOAD_UNAVAILABLE","A current Week or Monthly plan and valid video access are required"))}
            catch(_: PlaybackDenied){respond(HttpStatusCode.NotFound,ApiResponse.error<Unit>("NOT_FOUND","Video unavailable"))}
            catch(_: PlaybackUnavailable){respond(HttpStatusCode.Conflict,ApiResponse.error<Unit>("NOT_READY","Video is not ready"))}
            catch(_: IllegalArgumentException){respond(HttpStatusCode.BadRequest,ApiResponse.error<Unit>("INVALID_REQUEST","Invalid download request"))}
        }
        fun ApplicationCall.clientId(): String {
            val value=request.headers["X-Vidoora-Download"] ?: throw IllegalArgumentException()
            require(java.util.UUID.fromString(value).toString()==value);return value
        }
        get("/quota") {call.action {account,_ ->call.respond(ApiResponse.success(service.quota(account)))}}
        post("/video/{videoId}") {call.action {account,token ->call.respond(ApiResponse.success(service.reserve(account,call.parameters["videoId"] ?: "",token,call.clientId())))}}
        get("/{id}/asset") {call.action {account,token ->
            val id=call.parameters["id"] ?: "";val path=call.request.queryParameters["path"] ?: ""
            val version=service.expectedVersion(account,id)
            val bytes=service.asset(account,id,path,token,call.clientId());val hash=digest(bytes)
            call.respondBytes(bytes,ContentType.Application.OctetStream)
            service.delivered(account,id,path,hash,call.clientId(),version) // Server-observed response plus per-asset proof, not a completion boolean.
        }}
        post("/{id}/complete") {call.action {account,token ->
            val channel=call.receiveChannel();val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            try {while(true){val n=channel.readAvailable(buffer,0,buffer.size);if(n<0) break;require(output.size()+n<=2*1024*1024);output.write(buffer,0,n)}} finally{channel.cancel(null)}
            val proof=try{Json.decodeFromString<DownloadProof>(output.toString(Charsets.UTF_8))}catch(_: Exception){throw IllegalArgumentException()}
            call.respond(ApiResponse.success(service.complete(account,call.parameters["id"] ?: "",token,proof,call.clientId())))
        }}
        delete("/{id}") {call.action {account,_ ->service.cancel(account,call.parameters["id"] ?: "",call.clientId());call.respond(ApiResponse.success(mapOf("cancelled" to true)))}}
    }}
}
