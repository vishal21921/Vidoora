package com.vidoora.backend

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.auth.AuthService
import com.vidoora.backend.modules.auth.JwtConfig
import com.vidoora.backend.modules.upload.UploadService
import com.vidoora.backend.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val jwtConfig = JwtConfig(environment.config)
    val storageService = S3StorageService(environment.config)
    val authService = AuthService(jwtConfig)
    val uploadService = UploadService(storageService)

    DatabaseFactory.init(environment.config)
    if (storageService.isMockMode) log.warn("Explicit mock storage mode: no real video bytes are stored")
    val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        while (isActive) {
            try { uploadService.cleanupExpiredSessions() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.error("Upload session cleanup failed", e) }
            delay(60 * 60 * 1000L)
        }
    }
    val deletionWorker=com.vidoora.backend.modules.deletion.VideoDeletionWorker(
        com.vidoora.backend.modules.deletion.PostgresVideoDeletionRepository(storageService.processedBucketName),storageService)
    val deletionCleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
        if(storageService.isMockMode) return@launch
        while(isActive) {
            try { for(index in 1..5) { if(!deletionWorker.runOne()) break } }
            catch(e: CancellationException) { throw e }
            catch (_: Exception) { log.warn("Video deletion cleanup deferred") }
            delay(30_000)
        }
    }
    environment.monitor.subscribe(ApplicationStopped) { cleanup.cancel(); deletionCleanup.cancel(); storageService.close() }

    val membership=com.vidoora.backend.modules.membership.MembershipRuntime.services(environment.config)
    val entitlements=com.vidoora.backend.modules.membership.RoleAwareViewerEntitlements(membership.entitlements)
    val paymentRecovery=membership.recovery?.let { service -> CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
        while(isActive) {
            try { for(index in 1..5) { if(!service.runOne()) break } }
            catch(e: CancellationException) { throw e }
            catch (_: Exception) { log.warn("Purchase recovery deferred") }
            delay(15_000)
        }
    } }
    environment.monitor.subscribe(ApplicationStopped) { paymentRecovery?.cancel() }
    configureSerialization()
    configureSecurity(jwtConfig)
    configureStatusPages()
    val playback=com.vidoora.backend.modules.playback.PlaybackService(storageService,shareTokens=com.vidoora.backend.modules.playback.VideoShareTokens(jwtConfig),entitlements=entitlements)
    configureRouting(authService,uploadService,playback,entitlements,membership.recovery,
        com.vidoora.backend.modules.downloads.PrivateDownloads(playback,membership.grants,benefits=entitlements),
        com.vidoora.backend.modules.analytics.VideoAnalytics(playback,jwtConfig.secret))
}



