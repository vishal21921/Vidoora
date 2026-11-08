package com.vidoora.backend.modules.processing

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.UUID
import kotlin.math.roundToInt

class InvalidMedia : RuntimeException("Unsupported or invalid video")
class LeaseLost : RuntimeException("Processing lease lost")
data class ProcessingClaim(val jobId: UUID, val videoId: UUID, val token: UUID,
    val rawKey: String, val bytes: Long, val bucket: String, val prefix: String)
data class Rendition(val width: Int, val height: Int, val bitrate: Int) {
    val name: String get() = "${height}p"
}
data class MediaInfo(val width: Int, val height: Int, val duration: Double, val hasAudio: Boolean)
data class ProcessingOutput(val media: MediaInfo, val renditions: List<Rendition>, val directory: Path)

object MediaPlan {
    fun probe(json: String): MediaInfo {
        val root = Json.parseToJsonElement(json).jsonObject
        val streams = root["streams"]?.jsonArray ?: throw InvalidMedia()
        val video = streams.map { it.jsonObject }.firstOrNull {
            it["codec_type"]?.jsonPrimitive?.content == "video" &&
                it["disposition"]?.jsonObject?.get("attached_pic")?.jsonPrimitive?.intOrNull != 1
        } ?: throw InvalidMedia()
        val width = video["width"]?.jsonPrimitive?.intOrNull ?: throw InvalidMedia()
        val height = video["height"]?.jsonPrimitive?.intOrNull ?: throw InvalidMedia()
        val duration = root["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull ?: throw InvalidMedia()
        val sar = video["sample_aspect_ratio"]?.jsonPrimitive?.content
        // Non-square-pixel sources need a deliberate display-aspect policy; never silently distort them.
        if (sar != null && sar !in setOf("1:1", "N/A", "0:1")) throw InvalidMedia()
        val rotation = video["side_data_list"]?.jsonArray?.mapNotNull {
            it.jsonObject["rotation"]?.jsonPrimitive?.doubleOrNull
        }?.firstOrNull() ?: video["tags"]?.jsonObject?.get("rotate")?.jsonPrimitive?.doubleOrNull ?: 0.0
        if (!rotation.isFinite() || rotation % 90.0 != 0.0) throw InvalidMedia()
        val rotate = (rotation.toInt() % 180) != 0
        val info = MediaInfo(if (rotate) height else width, if (rotate) width else height,
            duration, streams.any { it.jsonObject["codec_type"]?.jsonPrimitive?.content == "audio" })
        validate(info)
        return info
    }
    fun validate(info: MediaInfo) {
        if (info.width !in 2..4096 || info.height !in 2..4096 ||
            !info.duration.isFinite() || info.duration <= 0 || info.duration > 7200 ||
            info.width.toDouble()/info.height !in 0.125..8.0) throw InvalidMedia()
    }
    fun renditions(info: MediaInfo): List<Rendition> {
        validate(info)
        // Bound both dimensions, including portrait sources; never upscale either dimension.
        val caps = listOf(640 to 360, 854 to 480, 1280 to 720)
        val result = caps.map { (maxWidth, maxHeight) ->
            val scale = minOf(1.0, maxWidth.toDouble()/info.width, maxHeight.toDouble()/info.height)
            val w = ((info.width * scale).toInt()/2*2).coerceAtLeast(2)
            val h = ((info.height * scale).toInt()/2*2).coerceAtLeast(2)
            Rendition(w, h, when { h <= 360 -> 800_000; h <= 480 -> 1_400_000; else -> 2_800_000 })
        }.distinctBy { it.width to it.height }
        return result
    }
    fun master(renditions: List<Rendition>, audio: Boolean): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n")
        for (r in renditions) {
            // Peak video rate plus AAC and TS overhead; access control is applied by future playback work.
            val peak = ((r.bitrate * 1.5 + if (audio) 128_000 else 0) * 1.15).roundToInt()
            append("#EXT-X-STREAM-INF:BANDWIDTH=$peak,RESOLUTION=${r.width}x${r.height}\n")
            append("${r.name}/index.m3u8\n")
        }
    }
}

interface ProcessingRepository {
    fun progress(claim: ProcessingClaim,percent: Int) {}
    fun claim(bucket: String): ProcessingClaim?
    fun heartbeat(claim: ProcessingClaim): Boolean
    fun complete(claim: ProcessingClaim, output: ProcessingOutput): Boolean
    fun fail(claim: ProcessingClaim, permanent: Boolean): Boolean
}
