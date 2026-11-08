package com.vidoora.backend
import com.vidoora.backend.modules.processing.ProcessingProgress
import org.junit.Assert.*
import org.junit.Test
class ProcessingProgressTest {
 @Test fun measuredProgressIsBoundedAndWeightedAcrossRenditions() {
    assertEquals(0,ProcessingProgress.percent(0,60.0,0,3))
    assertEquals(14,ProcessingProgress.percent(30_000_000,60.0,0,3))
    assertEquals(85,ProcessingProgress.percent(120_000_000,60.0,2,3))
 }
 @Test fun scopedSinkIsClearedAfterFailure() {
    val values=mutableListOf<Int>()
    runCatching {ProcessingProgress.track(values::add) {ProcessingProgress.report(12);error("fixture")}}
    ProcessingProgress.report(30)
    assertEquals(listOf(12),values)
 }
}
