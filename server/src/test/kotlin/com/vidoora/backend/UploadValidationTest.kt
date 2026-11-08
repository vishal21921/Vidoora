package com.vidoora.backend

import com.vidoora.backend.modules.upload.*
import org.junit.Assert.*
import org.junit.Test

class UploadValidationTest {
    @Test fun partCountsAtBoundaries() {
        assertEquals(1, UploadValidation.partCount(1))
        assertEquals(1, UploadValidation.partCount(UploadValidation.CHUNK_BYTES.toLong()))
        assertEquals(2, UploadValidation.partCount(UploadValidation.CHUNK_BYTES + 1L))
        assertEquals(205, UploadValidation.partCount(UploadValidation.MAX_BYTES))
    }
    @Test fun completionSortsRealEtags() {
        val result = UploadValidation.orderedParts(listOf(PartTagRequest(2, "\"b\""), PartTagRequest(1, "\"a\"")), 2)
        assertEquals(listOf(1, 2), result.map { it.partNumber })
        assertEquals("\"a\"", result.first().eTag)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsIncompleteCompletion() { UploadValidation.orderedParts(listOf(PartTagRequest(1, "tag")), 2) }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateCompletion() { UploadValidation.orderedParts(listOf(PartTagRequest(1, "tag"), PartTagRequest(1, "tag")), 2) }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingEtag() { UploadValidation.orderedParts(listOf(PartTagRequest(1, "")), 1) }
    @Test(expected = IllegalArgumentException::class) fun refreshRejectsZero() { UploadValidation.validatePartNumbers(listOf(0), 2) }
    @Test(expected = IllegalArgumentException::class) fun refreshRejectsOutOfBounds() { UploadValidation.validatePartNumbers(listOf(3), 2) }
    @Test(expected = IllegalArgumentException::class) fun refreshRejectsDuplicates() { UploadValidation.validatePartNumbers(listOf(1, 1), 2) }
}
