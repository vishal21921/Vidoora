package com.example.vidoora

import com.example.vidoora.core.work.MultipartPlan
import org.junit.Assert.*
import org.junit.Test

class MultipartPlanTest {
    @Test fun byteRangesCoverEveryByteWithoutOverlap() {
        for (size in listOf(1L, 5_242_879L, 5_242_880L, 5_242_881L, MultipartPlan.MAX_BYTES)) {
            val ranges = MultipartPlan.ranges(size, MultipartPlan.MIN_CHUNK)
            assertEquals(0L, ranges.first().startByte)
            assertEquals(size - 1, ranges.last().endByte)
            assertEquals(size, ranges.sumOf { it.sizeBytes })
            ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.endByte + 1, b.startByte) }
            ranges.dropLast(1).forEach { assertEquals(MultipartPlan.MIN_CHUNK.toLong(), it.sizeBytes) }
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversize() { MultipartPlan.ranges(MultipartPlan.MAX_BYTES + 1, MultipartPlan.MIN_CHUNK) }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroBytes() { MultipartPlan.ranges(0, MultipartPlan.MIN_CHUNK) }
    @Test(expected = IllegalArgumentException::class) fun rejectsSmallChunks() { MultipartPlan.ranges(100, 4) }
    @Test(expected = IllegalArgumentException::class) fun missingEtagIsNeverInvented() { MultipartPlan.eTag(null) }
    @Test(expected = IllegalArgumentException::class) fun weakEtagIsRejected() { MultipartPlan.eTag("W/\"tag\"") }
    @Test fun etagIsPreserved() { assertEquals("\"opaque-storage-tag\"", MultipartPlan.eTag(" \"opaque-storage-tag\" ")) }
    @Test fun retryClassificationAndTerminalStates() {
        listOf(408, 429, 500, 503).forEach { assertTrue(MultipartPlan.retryable(it)) }
        listOf(400, 401, 403, 404).forEach { assertFalse(MultipartPlan.retryable(it)) }
        listOf("QUEUED", "UPLOADING", "RETRYING", "COMPLETING", "CANCELLING").forEach { assertTrue(MultipartPlan.runnable(it)) }
        listOf("PAUSED", "FAILED", "UPLOADED", "CANCELLED").forEach { assertFalse(MultipartPlan.runnable(it)) }
    }
}
