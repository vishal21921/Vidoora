package com.vidoora.backend.modules.playback

import com.vidoora.backend.common.ApiResponse
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private suspend fun ApplicationCall.videoAction(action: suspend ()->Unit) {
    response.header(HttpHeaders.CacheControl,"no-store")
    try { action() }
    catch (_: PlaybackDenied) { respond(HttpStatusCode.NotFound,ApiResponse.error<Unit>("NOT_FOUND","Video unavailable")) }
    catch (_: PlaybackQualityRestricted) { respond(HttpStatusCode.Forbidden,ApiResponse.error<Unit>("QUALITY_LIMIT","This plan supports video up to 480p")) }
    catch (_: DeletionBusy) { respond(HttpStatusCode.Conflict,ApiResponse.error<Unit>("UPLOAD_ACTIVE","Cancel the active upload before deleting this video")) }
    catch (_: PlaybackUnavailable) { respond(HttpStatusCode.Conflict,ApiResponse.error<Unit>("NOT_READY","Video is not ready for this action")) }
}
fun Route.playbackRoutes(service: PlaybackService) {
    authenticate("auth-jwt") {
        post("/api/v1/videos/mine/status") {
            call.videoAction { call.respond(ApiResponse.success(service.libraryStatuses(call.principal<JWTPrincipal>()!!.subject!!,call.receive<OwnerVideoStatusRequest>()))) }
        }
        get("/api/v1/folders") { call.videoAction { call.respond(ApiResponse.success(service.folders(call.principal<JWTPrincipal>()!!.subject!!))) } }
        post("/api/v1/folders") { call.videoAction { call.respond(ApiResponse.success(service.saveFolder(call.principal<JWTPrincipal>()!!.subject!!,null,call.receive<FolderNameRequest>()))) } }
        patch("/api/v1/folders/{id}") { call.videoAction { call.respond(ApiResponse.success(service.saveFolder(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: "",call.receive<FolderNameRequest>()))) } }
        delete("/api/v1/folders/{id}") { call.videoAction { call.respond(ApiResponse.success(service.removeFolder(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: ""))) } }
        put("/api/v1/videos/{id}/folder") { call.videoAction { call.respond(ApiResponse.success(service.moveToFolder(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: "",call.receive<MoveFolderRequest>().folderId))) } }
        delete("/api/v1/videos/{id}") {
            call.videoAction { call.respond(ApiResponse.success(service.deleteVideo(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: ""))) }
        }
        post("/api/v1/videos/{id}/block-uploader") {
            call.videoAction { call.respond(ApiResponse.success(service.blockUploader(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: "",call.request.headers["X-Vidoora-Share"]))) }
        }
        get("/api/v1/users/me/blocked-uploaders") {
            call.videoAction { call.respond(ApiResponse.success(service.blockedUploaders(call.principal<JWTPrincipal>()!!.subject!!,call.request.queryParameters["cursor"]))) }
        }
        delete("/api/v1/users/me/blocked-uploaders/{uploaderId}") {
            call.videoAction { call.respond(ApiResponse.success(service.unblockUploader(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["uploaderId"] ?: ""))) }
        }
        get("/api/v1/videos/mine/page") {
            call.videoAction {
                val limit=call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid page size") } ?: 25
                call.respond(ApiResponse.success(service.libraryPage(call.principal<JWTPrincipal>()!!.subject!!,
                    call.request.queryParameters["q"] ?: "",call.request.queryParameters["cursor"],limit,call.request.queryParameters["sort"] ?: "NEWEST",
                    call.request.queryParameters["state"] ?: "ALL",call.request.queryParameters["sharing"] ?: "ALL",call.request.queryParameters["folder"] ?: "ALL")))
            }
        }
        patch("/api/v1/videos/{id}/details") {
            call.videoAction {
                val request=call.receive<EditVideoRequest>()
                call.respond(ApiResponse.success(service.editDetails(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: "",request)))
            }
        }
        get("/api/v1/videos/mine") {
            try {
                call.response.header(HttpHeaders.CacheControl,"no-store")
                call.respond(ApiResponse.success(service.library(call.principal<JWTPrincipal>()!!.subject!!)))
            } catch (_: PlaybackDenied) { call.respond(HttpStatusCode.Forbidden,ApiResponse.error<Unit>("FORBIDDEN","Video library unavailable")) }
        }
        get("/api/v1/videos/discover") {
            call.videoAction {
                val raw=call.request.queryParameters["limit"]
                val limit=if(raw==null) 20 else raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid page size")
                call.respond(ApiResponse.success(service.discover(call.principal<JWTPrincipal>()!!.subject!!,call.request.queryParameters["cursor"],limit)))
            }
        }
        put("/api/v1/videos/{id}/thumbnail") {
            call.videoAction {
                require(call.request.contentType().withoutParameters()==ContentType.Image.JPEG) { "JPEG thumbnail required" }
                val channel=call.receiveChannel()
                val bytes=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                try {
                    while(true) {
                        val count=channel.readAvailable(buffer,0,buffer.size)
                        if(count<0) break
                        require(bytes.size()+count<=ThumbnailImages.MAX_BYTES) { "Thumbnail exceeds 256 KiB" }
                        bytes.write(buffer,0,count)
                    }
                } finally { channel.cancel(null) }
                call.respond(ApiResponse.success(service.changeThumbnail(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: "",bytes.toByteArray())))
            }
        }
        post("/api/v1/videos/{id}/publish") {
            call.videoAction {
                val request=call.receive<PublishRequest>()
                call.respond(ApiResponse.success(service.publish(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: "",request.visibility)))
            }
        }
        post("/api/v1/videos/{id}/unpublish") {
            call.videoAction { call.respond(ApiResponse.success(service.unpublish(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: ""))) }
        }
        post("/api/v1/videos/{id}/share-link") {
            call.videoAction { call.respond(ApiResponse.success(service.shareLink(call.principal<JWTPrincipal>()!!.subject!!,call.parameters["id"] ?: ""))) }
        }

    }
    // A valid share capability replaces login only for read-only details/HLS.
    authenticate("auth-jwt", strategy = AuthenticationStrategy.Optional) {
        get("/api/v1/videos/{id}/thumbnail") {
            call.videoAction {
                call.response.header("X-Content-Type-Options","nosniff")
                call.respondBytes(service.thumbnail(call.principal<JWTPrincipal>()?.subject,call.parameters["id"] ?: "",call.request.headers["X-Vidoora-Share"]),ContentType.Image.JPEG)
            }
        }
        get("/api/v1/videos/{id}/details") {
            call.videoAction { call.respond(ApiResponse.success(service.details(call.principal<JWTPrincipal>()?.subject,
                call.parameters["id"] ?: "",call.request.headers["X-Vidoora-Share"]))) }
        }
        get("/api/v1/videos/{id}/playback/{asset...}") {
            call.videoAction {
                val asset=call.parameters.getAll("asset")?.joinToString("/") ?: ""
                val bytes=service.asset(call.principal<JWTPrincipal>()?.subject,call.parameters["id"] ?: "",asset,call.request.headers["X-Vidoora-Share"])
                call.response.header("X-Content-Type-Options","nosniff")
                call.respondBytes(bytes,if(asset.endsWith(".m3u8")) ContentType.parse("application/vnd.apple.mpegurl") else ContentType.parse("video/mp2t"))
            }
        }
    }

}










