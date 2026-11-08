package com.vidoora.backend.modules.processing

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class FfmpegTranscoder(private val ffmpeg: String, private val ffprobe: String) {
    fun verifyTools() {
        run(listOf(ffmpeg,"-version"), 15, { true })
        run(listOf(ffprobe,"-version"), 15, { true })
    }
    private fun run(args: List<String>, timeout: Long, alive: ()->Boolean, directory: Path? = null, progressFile: Path?=null, onProgress: (Long)->Unit={}): String {
        // Argument array, never a shell. Never return raw FFmpeg diagnostics to API/logs.
        val output = Files.createTempFile("vidoora-process-", ".log")
        val builder = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile())
        if (directory != null) builder.directory(directory.toFile())
        val process = builder.start()
        val shutdownHook = Thread {
            process.descendants().forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout)
        try {
            while (!process.waitFor(1,TimeUnit.SECONDS)) {
                if(progressFile!=null && Files.exists(progressFile)) {
                    java.io.RandomAccessFile(progressFile.toFile(),"r").use { file ->
                        file.seek((file.length()-4096).coerceAtLeast(0))
                        val bytes=ByteArray((file.length()-file.filePointer).toInt().coerceAtMost(4096))
                        file.readFully(bytes)
                        String(bytes,Charsets.UTF_8).lineSequence().lastOrNull {it.startsWith("out_time_us=")}?.substringAfter('=')?.toLongOrNull()?.let(onProgress)
                    }
                }
                if (Thread.currentThread().isInterrupted || !alive()) throw LeaseLost()
                if (System.nanoTime()>deadline) throw IllegalStateException("Transcode timeout")
                check(Files.size(output)<=2*1024*1024) { "Process output exceeded limit" }
                if (directory != null) check(directoryBytes(directory)<=6L*1024*1024*1024) { "Processing disk budget exceeded" }
            }
            if (!alive()) throw LeaseLost()
            check(process.exitValue()==0) { "Media tool failed" }
            check(Files.size(output)<=2*1024*1024) { "Process output exceeded limit" }
            return Files.readString(output)
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) { }
            process.descendants().forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(10,TimeUnit.SECONDS)
            Files.deleteIfExists(output)
        }
    }
    fun thumbnail(input: Path,output: Path,alive: ()->Boolean) {
        run(listOf(ffmpeg,"-hide_banner","-loglevel","error","-nostdin","-y","-protocol_whitelist","file,pipe",
            "-format_whitelist","mov,matroska,webm,avi,mpegts,flv,ogg","-threads","2","-t","2","-i",input.toString(),
            "-map","0:V:0","-an","-sn","-dn","-map_metadata","-1","-map_chapters","-1",
            "-vf","scale=480:270:force_original_aspect_ratio=decrease,pad=480:270:(ow-iw)/2:(oh-ih)/2,setsar=1,thumbnail=30",
            "-frames:v","1","-c:v","mjpeg","-threads","2","-q:v","4","-update","1",output.toString()),30,alive,output.parent)
        val size=Files.size(output)
        check(size in 4..(256*1024).toLong()) { "Invalid thumbnail size" }
        Files.newInputStream(output).use { check(it.read()==255 && it.read()==216) { "Invalid thumbnail format" } }
    }
    fun transcode(input: Path, output: Path, alive: ()->Boolean): ProcessingOutput {
        val json = try { run(listOf(ffprobe,"-v","error","-protocol_whitelist","file,pipe",
            "-format_whitelist","mov,matroska,webm,avi,mpegts,flv,ogg","-show_streams","-show_format","-of","json",input.toString()),60,alive) }
        catch (e: LeaseLost) { throw e }
        catch (_: IllegalStateException) { throw InvalidMedia() }
        val media = try { MediaPlan.probe(json) } catch (_: RuntimeException) { throw InvalidMedia() }
        val renditions = MediaPlan.renditions(media)
        Files.createDirectories(output)
        // Sequential renditions limit CPU/memory for the initial single-host worker.
        val wholeDeadline = System.nanoTime()+TimeUnit.HOURS.toNanos(1)
        for ((renditionIndex,r) in renditions.withIndex()) {
            val variant = output.resolve(r.name)
            Files.createDirectories(variant)
            val args = mutableListOf(ffmpeg,"-hide_banner","-loglevel","error","-nostdin","-y",
                "-protocol_whitelist","file,pipe","-format_whitelist","mov,matroska,webm,avi,mpegts,flv,ogg",
                "-threads","2","-i",input.toString(),"-map","0:V:0")
            if (media.hasAudio) args += listOf("-map","0:a:0","-c:a","aac","-b:a","128k","-ac","2","-ar","48000")
            else args += "-an"
            args += listOf("-map_metadata","-1","-map_chapters","-1","-sn","-dn","-vf",
                "scale=${r.width}:${r.height},setsar=1,fps=30","-c:v","libx264","-threads","2","-preset","veryfast",
                "-pix_fmt","yuv420p","-b:v",r.bitrate.toString(),"-maxrate",(r.bitrate*3/2).toString(),
                "-bufsize",(r.bitrate*3).toString(),"-g","180","-keyint_min","180","-sc_threshold","0",
                "-force_key_frames","expr:gte(t,n_forced*6)","-f","hls","-hls_time","6","-hls_list_size","0",
                "-hls_playlist_type","vod","-hls_flags","independent_segments","-hls_segment_filename",
                "${r.name}/segment_%06d.ts","${r.name}/index.m3u8")
            val progressFile=variant.resolve("ffmpeg-progress.txt")
            args.addAll(1,listOf("-progress",progressFile.toString(),"-nostats"))
            val seconds = TimeUnit.NANOSECONDS.toSeconds(wholeDeadline-System.nanoTime()).coerceAtLeast(1)
            try {run(args,seconds,alive,output,progressFile) {micros ->
                ProcessingProgress.report(ProcessingProgress.percent(micros,media.duration,renditionIndex,renditions.size))
            }} finally {Files.deleteIfExists(progressFile)}
            ProcessingProgress.report(((renditionIndex+1)*85/renditions.size))
            val playlist = Files.readString(variant.resolve("index.m3u8"))
            check(playlist.contains("#EXT-X-ENDLIST") && playlist.contains("#EXTINF:")) { "Incomplete HLS playlist" }
            for (line in playlist.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }) {
                check(Regex("segment_[0-9]{6}\\.ts").matches(line)) { "Invalid segment path" }
                check(Files.size(variant.resolve(line))>0) { "Empty HLS segment" }
            }
        }
        check(directoryBytes(output)<=6L*1024*1024*1024)
        Files.writeString(output.resolve("master.m3u8"),MediaPlan.master(renditions,media.hasAudio))
        // A missing preview must not discard successfully generated HLS; backfill can retry it.
        try { thumbnail(input,output.resolve("thumbnail.jpg"),alive) }
        catch(e: LeaseLost) { throw e }
        catch (_: Exception) { Files.deleteIfExists(output.resolve("thumbnail.jpg")); println("Thumbnail deferred; video processing continues") }
        return ProcessingOutput(media,renditions,output)
    }
    private fun directoryBytes(directory: Path): Long = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
    }
}

