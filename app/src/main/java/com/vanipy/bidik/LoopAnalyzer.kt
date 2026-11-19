package com.vanipy.bidik

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Samples a saved recording into LoopFinder frames and runs the loop
 * search. Two passes: a coarse pass over the whole clip (5 fps, capped
 * at [MAX_COARSE_FRAMES] frames), then a fine pass (20 fps) around the
 * best candidate's endpoints so the exported loop starts and ends on
 * the closest matching frames, not the nearest coarse sample.
 *
 * When the "system bars: hidden" option crops a band out of the video,
 * the same band is skipped in the matching grids so a clock or icon
 * change there cannot affect the seam score.
 */
object LoopAnalyzer {

    private const val COARSE_FPS = 5
    private const val FINE_FPS = 20
    private const val MAX_COARSE_FRAMES = 400
    private const val REFINE_WINDOW_MS = 400L
    // 64x36 keeps small on-screen animations (a few dp of a widget)
    // resolvable in the matching grids; 32x18 averaged them away.
    const val GRID_W = 64
    const val GRID_H = 36

    fun analyze(
        src: File,
        maskTopFraction: Float = 0f,
        maskBottomFraction: Float = 0f,
        onProgress: ((sampled: Int, total: Int) -> Unit)? = null
    ): LoopFinder.Result {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(src.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return LoopFinder.Result(emptyList(), null)
            val stepMs = 1000L / COARSE_FPS
            val frameCount = ((durationMs / stepMs).toInt() + 1).coerceAtMost(MAX_COARSE_FRAMES)
            val skipTop = (GRID_H * maskTopFraction).toInt()
            val skipBottom = (GRID_H * maskBottomFraction).toInt()
            val frames = ArrayList<LoopFinder.Frame>(frameCount)
            for (k in 0 until frameCount) {
                val t = k * stepMs
                if (t >= durationMs) break
                val grid = sampleGrid(retriever, t * 1000L) ?: break
                frames.add(LoopFinder.Frame(t, GRID_W, GRID_H, grid, skipTop, skipBottom))
                onProgress?.invoke(frames.size, frameCount)
            }
            val coarse = LoopFinder.find(frames)
            val top = coarse.candidates.firstOrNull() ?: return coarse
            // Refine scores endpoints against the same motion weights the
            // coarse pass found, so a small animated region keeps driving
            // the match at 20 fps too.
            val weights = LoopFinder.activityWeights(frames) ?: return coarse
            val refined = refine(retriever, top, skipTop, skipBottom, weights) ?: return coarse
            return LoopFinder.Result(listOf(refined) + coarse.candidates.drop(1), null)
        } finally {
            retriever.release()
        }
    }

    /**
     * Resamples around the coarse endpoints at [FINE_FPS] and returns
     * the strongest pair nearby. The start is chosen first (closest
     * fine frame matching the coarse end), then the end against it.
     */
    private fun refine(
        retriever: MediaMetadataRetriever,
        coarse: LoopFinder.Candidate,
        skipTop: Int,
        skipBottom: Int,
        weights: DoubleArray
    ): LoopFinder.Candidate? {
        val startGrid = window(retriever, coarse.startMs) ?: return null
        val endGrid = window(retriever, coarse.endMs) ?: return null
        fun frameAt(t: Long, luma: ByteArray) =
            LoopFinder.Frame(t, GRID_W, GRID_H, luma, skipTop, skipBottom)
        var bestStart: LoopFinder.Frame? = null
        var bestStartSeam = -1.0
        for ((t, luma) in startGrid) {
            val seam = LoopFinder.similarity(
                frameAt(t, luma), frameAt(coarse.endMs, endGrid[0].second), weights
            )
            if (seam > bestStartSeam) {
                bestStartSeam = seam
                bestStart = frameAt(t, luma)
            }
        }
        val start = bestStart ?: return null
        var best: LoopFinder.Candidate? = null
        for ((t, luma) in endGrid) {
            // The fine windows around the coarse endpoints can overlap
            // for short spans; never let the end collapse onto (or
            // before) the start - a zero-length "loop" always matches
            // itself perfectly.
            if (t - start.timeMs < LoopFinder.MIN_LOOP_MS) continue
            val seam = LoopFinder.similarity(start, frameAt(t, luma), weights)
            if (best == null || seam > best.seam) {
                best = LoopFinder.Candidate(start.timeMs, t, seam)
            }
        }
        return best
    }

    /** Frames at [FINE_FPS] within ±[REFINE_WINDOW_MS] of [centerMs]. */
    private fun window(
        retriever: MediaMetadataRetriever,
        centerMs: Long
    ): List<Pair<Long, ByteArray>>? {
        val stepMs = 1000L / FINE_FPS
        val out = ArrayList<Pair<Long, ByteArray>>()
        var t = (centerMs - REFINE_WINDOW_MS).coerceAtLeast(0)
        while (t <= centerMs + REFINE_WINDOW_MS) {
            val grid = sampleGrid(retriever, t * 1000L) ?: return if (out.isEmpty()) null else out
            out.add(t to grid)
            t += stepMs
        }
        return if (out.isEmpty()) null else out
    }

    /** Decodes the frame nearest [timeUs] and reduces it to a luma grid. */
    internal fun sampleGrid(retriever: MediaMetadataRetriever, timeUs: Long): ByteArray? {
        val bitmap = try {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        } ?: return null
        val grid = Bitmap.createScaledBitmap(bitmap, GRID_W, GRID_H, true)
        if (grid != bitmap) bitmap.recycle()
        val pixels = IntArray(GRID_W * GRID_H)
        grid.getPixels(pixels, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        grid.recycle()
        val luma = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = (((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3).toByte()
        }
        return luma
    }
}
