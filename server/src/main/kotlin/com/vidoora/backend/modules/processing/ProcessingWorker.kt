package com.vidoora.backend.modules.processing

import com.vidoora.backend.infrastructure.S3StorageService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ProcessingRunResult { NO_JOB, COMPLETED, FAILED }

class ProcessingWorker(private val repository: ProcessingRepository, private val storage: S3StorageService,
    private val scratch: Path, private val transcode: (Path,Path,()->Boolean)->ProcessingOutput) {
    fun runOne(): ProcessingRunResult {
        Files.createDirectories(scratch)
        // Avoid claiming a job when this host cannot hold an original and bounded output.
        check(Files.getFileStore(scratch).usableSpace>8L*1024*1024*1024) { "Processing requires 8 GiB free scratch space" }
        val claim = repository.claim(storage.processedBucketName) ?: return ProcessingRunResult.NO_JOB
        val alive = AtomicBoolean(true)
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleAtFixedRate({
            try { if (!repository.heartbeat(claim)) alive.set(false) }
            catch (_: Exception) { alive.set(false) }
        },20,20,TimeUnit.SECONDS)
        var work: Path? = null
        var stage = "PREPARE"
        var result = ProcessingRunResult.FAILED
        try {
            work = Files.createTempDirectory(scratch,"vidoora-attempt-")
            val input = work.resolve("original.media")
            stage = "DOWNLOAD"
            storage.downloadOriginal(claim.rawKey,input,claim.bytes)
            if (!alive.get()) throw LeaseLost()
            stage = "TRANSCODE"
            var lastPercent=-1
            val output = ProcessingProgress.track({ percent ->
                if(percent!=lastPercent) {repository.progress(claim,percent);lastPercent=percent}
            }) { transcode(input,work.resolve("hls")) { alive.get() && !Thread.currentThread().isInterrupted } }
            stage = "UPLOAD"
            Files.walk(output.directory).use { files ->
                // All segments first, media playlists second, master last. Never commit a partial manifest.
                val ordered = files.filter { Files.isRegularFile(it) }.toList().sortedBy {
                    when { it.fileName.toString()=="master.m3u8" -> 2; it.toString().endsWith(".m3u8") -> 1; else -> 0 }
                }
                for ((fileIndex,file) in ordered.withIndex()) {
                    if (!alive.get() || !repository.heartbeat(claim)) throw LeaseLost()
                    val relative = output.directory.relativize(file).toString().replace('\\','/')
                    storage.uploadProcessed(claim.bucket,claim.prefix+relative,file)
                    repository.progress(claim,85+((fileIndex+1)*14/ordered.size))
                }
            }
            stage = "COMMIT"
            if (!alive.get() || !repository.complete(claim,output)) throw LeaseLost()
            println("Processing completed for job ${claim.jobId}")
            result = ProcessingRunResult.COMPLETED
        } catch (e: Exception) {
            // Generic diagnostic codes only: raw tool/storage exceptions can contain private information.
            val missingOriginal = stage == "DOWNLOAD" && e is software.amazon.awssdk.services.s3.model.NoSuchKeyException
            try { repository.fail(claim,e is InvalidMedia || missingOriginal) } catch (_: Exception) { /* lease sweep recovers */ }
            println("Processing attempt ended: " + when { missingOriginal -> "ORIGINAL_MISSING"; e is InvalidMedia -> "INVALID_MEDIA"; e is LeaseLost -> "LEASE_LOST"; else -> "RETRYABLE_FAILURE" })
            // Stage and exception class are safe diagnostic codes; never print provider/tool messages.
            println("Processing diagnostic: stage=$stage, type=${e.javaClass.simpleName}, cause=${e.cause?.javaClass?.simpleName ?: "none"}")
        } finally {
            alive.set(false); executor.shutdownNow(); executor.awaitTermination(10,TimeUnit.SECONDS)
            if (work != null) removeAttemptDirectory(scratch,work)
        }
        return result
    }
    companion object {
        fun removeAttemptDirectory(root: Path, directory: Path) {
            val absoluteRoot = root.toAbsolutePath().normalize()
            val target = directory.toAbsolutePath().normalize()
            require(target.parent==absoluteRoot && target.fileName.toString().startsWith("vidoora-attempt-"))
            Files.walk(target).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        fun cleanupOldScratch(root: Path) {
            Files.createDirectories(root)
            Files.list(root).use { paths -> paths.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) &&
                it.fileName.toString().startsWith("vidoora-attempt-") &&
                Files.getLastModifiedTime(it).toInstant().isBefore(Instant.now().minusSeconds(86400))
            }.forEach { removeAttemptDirectory(root,it) } }
        }
    }
}
