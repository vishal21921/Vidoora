package com.vidoora.backend.modules.processing

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.infrastructure.S3StorageService
import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import java.nio.file.Path

fun main(args: Array<String>) {
    val config = HoconApplicationConfig(ConfigFactory.load())
    require(config.property("database.driverClassName").getString()=="org.postgresql.Driver") { "Processor requires PostgreSQL" }
    require(config.propertyOrNull("r2.mockMode")?.getString()?.toBoolean()!=true) { "Processor does not accept mock storage" }
    val transcoder = FfmpegTranscoder(System.getenv("FFMPEG_PATH") ?: "ffmpeg", System.getenv("FFPROBE_PATH") ?: "ffprobe")
    transcoder.verifyTools() // Fail before migrations/job claims if tools are missing.
    DatabaseFactory.init(config)
    try {
    com.vidoora.backend.modules.analytics.ProcessorHeartbeat().use {
    val repository = PostgresProcessingRepository()
    val scratch = Path.of(System.getenv("PROCESSING_WORK_DIR") ?: "build/processing-work").toAbsolutePath().normalize()
    ProcessingWorker.cleanupOldScratch(scratch)
    S3StorageService(config).use { storage ->
        val thumbnails=ThumbnailWorker(ThumbnailRepository(),storage,scratch,transcoder::thumbnail)
        val worker = ProcessingWorker(repository,storage,scratch,transcoder::transcode)
        println("Vidoora processor ready (one job at a time; private HLS output)")
        try {
            if(args.contains("--thumbnails")) {
                // A bounded maintenance pass; failed attempts remain delayed/capped rather than looping.
                repeat(100) {
                    when(thumbnails.runOne()) {
                        ProcessingRunResult.NO_JOB -> { println("No thumbnail backfill is due"); return }
                        ProcessingRunResult.FAILED -> error("Thumbnail attempt deferred; retry later")
                        ProcessingRunResult.COMPLETED -> Unit
                    }
                }
                println("Thumbnail pass reached 100 videos; run again if needed")
                return
            }
            do {
                try { repository.cleanupOne(storage::deleteProcessingPrefix) }
                catch (_: Exception) { println("Processing cleanup deferred; will retry") }
                val result = worker.runOne()
                if(result==ProcessingRunResult.NO_JOB) thumbnails.runOne()
                if (args.contains("--once")) {
                    check(result != ProcessingRunResult.FAILED) { "Processing attempt failed; inspect safe diagnostic codes above" }
                    if (result == ProcessingRunResult.NO_JOB) println("No queued processing job is due")
                    break
                }
                if (result == ProcessingRunResult.NO_JOB) Thread.sleep(5000)
            } while (!Thread.currentThread().isInterrupted)
        } finally { /* Heartbeat is closed before the shared database pool. */ }
    }
    }
    } finally { DatabaseFactory.close() }
}

