package com.vidoora.backend.modules.playback

class PlaybackQualityRestricted : RuntimeException()

/** Only backend-owned, validated relative VOD playlists reach this filter. */
object PlaybackQuality {
    fun permits(path: String, maxHeight: Int?): Boolean = maxHeight == null || path == "master.m3u8" ||
        (path.substringBefore('p').toIntOrNull()?.let { it <= maxHeight } == true)

    fun master(text: String, maxHeight: Int?): String {
        PlaybackPaths.validateManifest("master.m3u8", text)
        if (maxHeight == null) return text
        val output = mutableListOf<String>()
        var stream: String? = null
        var kept = 0
        for (line in text.lineSequence()) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> { check(stream == null); stream = line }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val header = checkNotNull(stream) { "Missing stream attributes" }
                    if (permits(line, maxHeight)) { output += header; output += line; kept++ }
                    stream = null
                }
                stream != null -> check(line.isBlank()) { "Invalid variant attributes" }
                else -> output += line
            }
        }
        check(stream == null) { "Missing variant path" }
        if (kept == 0) throw PlaybackQualityRestricted()
        return output.joinToString("\n").also { PlaybackPaths.validateManifest("master.m3u8", it) }
    }
}
