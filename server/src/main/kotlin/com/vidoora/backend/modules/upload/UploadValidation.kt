package com.vidoora.backend.modules.upload

object UploadValidation {
    const val MAX_BYTES = 1_073_741_824L
    const val CHUNK_BYTES = 5_242_880

    fun partCount(bytes: Long, chunkBytes: Int = CHUNK_BYTES): Int {
        require(bytes in 1..MAX_BYTES && chunkBytes > 0) { "Invalid multipart size" }
        return ((bytes - 1) / chunkBytes + 1).toInt()
    }

    fun validatePartNumbers(numbers: List<Int>, total: Int) {
        require(numbers.isNotEmpty()) { "At least one part number is required" }
        require(numbers.size == numbers.distinct().size) { "Duplicate part numbers" }
        require(numbers.all { it in 1..total }) { "Part number outside upload bounds" }
    }

    fun orderedParts(parts: List<PartTagRequest>, total: Int): List<PartTagRequest> {
        validatePartNumbers(parts.map { it.partNumber }, total)
        require(parts.size == total) { "All parts are required before completion" }
        require(parts.all { it.eTag.isNotBlank() && !it.eTag.startsWith("W/") }) { "A valid storage ETag is required" }
        return parts.sortedBy { it.partNumber }.map { it.copy(eTag = it.eTag.trim()) }
    }
}
