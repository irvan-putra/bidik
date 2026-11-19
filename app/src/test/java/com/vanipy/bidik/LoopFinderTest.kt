package com.vanipy.bidik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loop matching on synthetic frame sequences: a toggling block is a
 * exact 800ms recurrence; a static screen and a one-way change are not.
 * The bar-mask test shows a changing status-bar band cannot break or
 * fake a match when those rows are skipped.
 */
class LoopFinderTest {

    private val w = 8
    private val h = 6

    /** Grid filled with [fill], with a bright one-cell block at [blockCol]. */
    private fun frameAt(
        timeMs: Long,
        blockCol: Int,
        fill: Int = 20,
        topRowNoise: Int = 0,
        skipTopRows: Int = 0,
        skipBottomRows: Int = 0
    ): LoopFinder.Frame {
        val luma = ByteArray(w * h) { fill.toByte() }
        for (row in 1 until h - 1) {
            luma[row * w + blockCol] = 220.toByte()
        }
        if (topRowNoise != 0) {
            for (col in 0 until w) luma[col] = topRowNoise.toByte()
        }
        return LoopFinder.Frame(timeMs, w, h, luma, skipTopRows, skipBottomRows)
    }

    /**
     * Block walks 0,1,2,3 across the grid: a clean 800ms loop with no
     * repeated frame inside a period.
     */
    private fun periodic(frames: Int, skipTop: Int = 0, topNoise: Boolean = false): List<LoopFinder.Frame> =
        (0 until frames).map { k ->
            frameAt(
                k * 200L,
                blockCol = k % 4,
                topRowNoise = if (topNoise) (k * 37) % 255 else 0,
                skipTopRows = skipTop
            )
        }

    @Test
    fun findsTheTightestCycle() {
        val result = LoopFinder.find(periodic(25)) // 5s sampled at 200ms
        assertTrue("expected at least one loop candidate", result.candidates.isNotEmpty())
        val top = result.candidates.first()
        assertEquals(800L, top.endMs - top.startMs)
        assertTrue("seam ${top.seam} too low for an exact loop", top.seam >= 0.99)
        assertNull(result.bestEffort)
    }

    @Test
    fun staticScreenIsNotALoop() {
        val frames = (0 until 25).map { k -> frameAt(k * 200L, blockCol = 0) }
        val result = LoopFinder.find(frames)
        assertTrue(result.candidates.isEmpty())
        // An identical match with no motion must not be offered as a guess.
        assertNull(result.bestEffort)
    }

    @Test
    fun monotonicChangeFindsNoCleanLoop() {
        val frames = (0 until 25).map { k ->
            frameAt(k * 200L, blockCol = 0, fill = 20 + k * 5)
        }
        val result = LoopFinder.find(frames)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun maskedBarRowsDoNotBreakTheMatch() {
        // The top row (a stand-in status bar) changes on every sample;
        // with those rows skipped the 800ms loop is found anyway.
        val masked = LoopFinder.find(periodic(25, skipTop = 1, topNoise = true))
        assertTrue(masked.candidates.isNotEmpty())
        assertEquals(800L, masked.candidates.first().let { it.endMs - it.startMs })
    }

    @Test
    fun nearMissIsOfferedAsBestEffort() {
        // The block returns every 800ms but the fill drifts on a cycle
        // that never realigns within the window: every return pose is
        // close yet below the strict seam threshold.
        val frames = (0 until 25).map { k ->
            frameAt(
                k * 200L,
                blockCol = k % 4,
                fill = 20 + (k % 7)
            )
        }
        val result = LoopFinder.find(frames, seamThreshold = 0.999)
        assertTrue(result.candidates.isEmpty())
        assertTrue("expected a best-effort guess", result.bestEffort != null)
    }
}
