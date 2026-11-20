package com.vanipy.bidik

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-side coverage for the trimmer's span math - the part that
 * decides what actually gets exported. Pixel behavior stays with the
 * emulator record-check.
 */
class TrimSelectionTest {

    @Test fun `fresh selection covers the whole recording`() {
        val s = TrimSelection(6000)
        assertEquals(0L, s.startMs)
        assertEquals(6000L, s.endMs)
    }

    @Test fun `setSpan clamps into the recording`() {
        val s = TrimSelection(6000)
        s.setSpan(-500, 99999)
        assertEquals(0L, s.startMs)
        assertEquals(6000L, s.endMs)
    }

    @Test fun `setSpan orders a reversed pair`() {
        val s = TrimSelection(6000)
        s.setSpan(4000, 1500)
        assertEquals(1500L, s.startMs)
        assertEquals(4000L, s.endMs)
    }

    @Test fun `setSpan enforces the minimum span around the center`() {
        val s = TrimSelection(6000)
        s.setSpan(3000, 3000)
        assertEquals(TrimSelection.MIN_SPAN_MS, s.endMs - s.startMs)
        val center = (s.startMs + s.endMs) / 2
        assertEquals(3000L, center)
    }

    @Test fun `minimum span survives at the tail edge`() {
        val s = TrimSelection(6000)
        s.setSpan(5980, 6000)
        assertEquals(TrimSelection.MIN_SPAN_MS, s.endMs - s.startMs)
        assertEquals(6000L, s.endMs)
    }

    @Test fun `a recording shorter than the minimum span stays whole`() {
        val s = TrimSelection(50)
        s.setSpan(10, 20)
        assertEquals(0L, s.startMs)
        assertEquals(50L, s.endMs)
    }

    @Test fun `moving the start handle past the end swaps roles`() {
        val s = TrimSelection(6000)
        s.setSpan(1000, 2000)
        s.moveHandle(true, 2500)
        // 2500 becomes the new end; the old end is the start.
        assertEquals(2000L, s.startMs)
        assertEquals(2500L, s.endMs)
    }

    @Test fun `candidate fill matches the detected span`() {
        val s = TrimSelection(12000)
        s.setSpan(6300, 12600) // a 6.3s detected loop in a 12s recording
        assertEquals(6300L, s.startMs)
        assertEquals(12000L, s.endMs)
    }
}
