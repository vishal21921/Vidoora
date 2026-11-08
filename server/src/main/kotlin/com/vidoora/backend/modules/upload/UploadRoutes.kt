package com.vidoora.backend.modules.upload

import com.vidoora.backend.common.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.uploadRoutes(uploadService: UploadService) {
    authenticate("auth-jwt") {
        route("/api/v1/videos") {
            post("/initiate-upload") {
                val principal = call.principal<JWTPrincipal>()
                val uploaderId = principal?.subject ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse.error<Unit>("UNAUTHORIZED", "Invalid authentication token")
                )
                val request = call.receive<InitiateUploadRequest>()
                val result = uploadService.initiateUpload(uploaderId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(result))
            }

            post("/{id}/complete-upload") {
                val principal = call.principal<JWTPrincipal>()
                val uploaderId = principal?.subject ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse.error<Unit>("UNAUTHORIZED", "Invalid authentication token")
                )
                val videoId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<Unit>("BAD_REQUEST", "Missing video id parameter")
                )
                val request = call.receive<CompleteUploadRequest>()
                val result = uploadService.completeUpload(uploaderId, videoId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(result))
            }

            post("/{id}/upload-parts/refresh") {
                val principal = call.principal<JWTPrincipal>()
                val uploaderId = principal?.subject ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse.error<Unit>("UNAUTHORIZED", "Invalid authentication token")
                )
                val videoId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<Unit>("BAD_REQUEST", "Missing video id parameter")
                )
                val request = call.receive<RefreshPartsRequest>()
                val result = uploadService.refreshUploadPartUrls(uploaderId, videoId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(result))
            }

            post("/{id}/abort-upload") {
                val principal = call.principal<JWTPrincipal>()
                val uploaderId = principal?.subject ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse.error<Unit>("UNAUTHORIZED", "Invalid authentication token")
                )
                val videoId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<Unit>("BAD_REQUEST", "Missing video id parameter")
                )
                val request = call.receive<AbortUploadRequest>()
                val result = uploadService.abortUpload(uploaderId, videoId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(result))
            }

            get("/{id}/status") {
                val videoId = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<Unit>("BAD_REQUEST", "Missing video id parameter")
                )
                val uploaderId = call.principal<JWTPrincipal>()?.subject
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val status = uploadService.getUploadStatus(uploaderId, videoId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(status))
            }
        }
    }
}
