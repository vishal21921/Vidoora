package com.example.vidoora.core.work

object MultipartPlan {
    const val MAX_BYTES = 1_073_741_824L
    const val MIN_CHUNK = 5_242_880
    data class Range(val partNumber: Int, val startByte: Long, val sizeBytes: Long) {
        val endByte: Long get() = startByte + sizeBytes - 1
    }
    fun ranges(bytes: Long, chunk: Int): List<Range> {
        require(bytes in 1..MAX_BYTES && chunk >= MIN_CHUNK) { "Invalid upload size or chunk size" }
        val count = ((bytes - 1) / chunk + 1).toInt()
        return (1..count).map { n ->
            val start = (n - 1L) * chunk
            Range(n, start, minOf(chunk.toLong(), bytes - start))
        }
    }
    fun eTag(raw: String?): String {
        val value = raw?.trim()
        require(!value.isNullOrBlank() && !value.startsWith("W/")) { "Storage response is missing a valid ETag" }
        return value
    }
    fun retryable(status: Int): Boolean = status == 408 || status == 429 || status >= 500
    fun runnable(status: String): Boolean = status in setOf("QUEUED", "UPLOADING", "RETRYING", "COMPLETING", "CANCELLING", "DELETING")
}
