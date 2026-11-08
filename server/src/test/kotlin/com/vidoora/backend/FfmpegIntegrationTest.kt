package com.vidoora.backend

import com.vidoora.backend.modules.processing.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class FfmpegIntegrationTest {
    @Test fun realVideoProducesDecodableVodHlsAndSilentSourceWorks() {
        assumeTrue("Explicit local FFmpeg opt-in required",System.getenv("VIDOORA_FFMPEG_TEST")=="true")
        val ffmpeg=System.getenv("FFMPEG_PATH") ?: "ffmpeg"
        val ffprobe=System.getenv("FFPROBE_PATH") ?: "ffprobe"
        val dir=Files.createTempDirectory("vidoora-attempt-ffmpeg-test-")
        fun exec(args: List<String>) {
            val process=ProcessBuilder(args).redirectErrorStream(true).redirectOutput(dir.resolve("tool.log").toFile()).start()
            try { assertTrue(process.waitFor(60,TimeUnit.SECONDS)); assertEquals(0,process.exitValue()) }
            finally { if(process.isAlive) process.destroyForcibly() }
        }
        try {
            for (audio in listOf(true,false)) {
                val input=dir.resolve("source-$audio.mp4")
                val args=mutableListOf(ffmpeg,"-v","error","-y","-f","lavfi","-i","testsrc2=size=1280x720:rate=30")
                if(audio) args += listOf("-f","lavfi","-i","sine=frequency=440:sample_rate=48000")
                args += listOf("-t","7","-c:v","libx264","-threads","2","-pix_fmt","yuv420p")
                if(audio) args += listOf("-c:a","aac")
                args += input.toString()
                exec(args)
                val result=FfmpegTranscoder(ffmpeg,ffprobe).transcode(input,dir.resolve("hls-$audio")) { true }
                val image=javax.imageio.ImageIO.read(result.directory.resolve("thumbnail.jpg").toFile())
                assertEquals(480,image.width); assertEquals(270,image.height)
                assertTrue(Files.size(result.directory.resolve("thumbnail.jpg"))<=256*1024)
                assertEquals(audio,result.media.hasAudio)
                assertEquals(listOf(360,480,720),result.renditions.map { it.height })
                assertTrue(Files.readString(result.directory.resolve("master.m3u8")).contains("720p/index.m3u8"))
                for(r in result.renditions) exec(listOf(ffmpeg,"-v","error","-i",result.directory.resolve("${r.name}/index.m3u8").toString(),"-f","null","-"))
            }
        } finally { ProcessingWorker.removeAttemptDirectory(dir.parent,dir) }
    }
}

