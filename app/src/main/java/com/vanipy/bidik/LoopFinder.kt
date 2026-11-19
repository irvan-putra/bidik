package com.vanipy.bidik

/**
 * Finds loop points in a sequence of screen frames: a start frame and a
 * later frame that shows (nearly) the same picture again, so that the
 * span between them plays as a seamless loop.
 *
 * Pure Kotlin/JVM on purpose: the frame sampling lives on Android
 * (LoopAnalyzer), the matching logic is unit-tested on the build host
 * with synthetic sequences.
 *
 * Frames are small luma grids (e.g. 32x18), so similarity is a mean
 * absolute difference over the grid. Two rules keep the match honest:
 *
 * - a candidate span must contain real motion, twice: the screen has
 *   to change at least two separate times inside the span (it goes
 *   somewhere AND comes back), and it has to depart from its start
 *   frame by at least [motionMin] somewhere in between. Without this,
 *   a static screen (or a static background dominating the average)
 *   "loops" trivially, and a one-time change - like a button relabel
 *   when recording starts - fakes a cycle.
 * - rows given as skipped (status bar / navigation bar bands when the
 *   recording crops them, see RecordOptions) play no part in the
 *   average, so a clock change in the bar cannot break or fake a match.
 *
 * The seam score is the endpoint similarity: 1.0 means the first and
 * last frame are identical, so the exported GIF wraps without a jump.
 * Candidates are ranked by seam (higher first), then by shorter span:
 * the tightest single cycle wins over many repeats of it.
 */
object LoopFinder {

    /** Shortest span that can play as a loop, in milliseconds. */
    const val MIN_LOOP_MS: Long = 400

    /** One sampled frame as a downscaled luma grid. */
    class Frame(
        val timeMs: Long,
        val gridW: Int,
        val gridH: Int,
        val luma: ByteArray,
        val skipTopRows: Int = 0,
        val skipBottomRows: Int = 0
    ) {
        init {
            require(luma.size == gridW * gridH)
        }
    }

    data class Candidate(val startMs: Long, val endMs: Long, val seam: Double)

    class Result(
        val candidates: List<Candidate>,
        /** Strongest pair below the seam threshold, when no candidate qualifies. */
        val bestEffort: Candidate?
    )

    fun similarity(a: Frame, b: Frame): Double {
        require(a.gridW == b.gridW && a.gridH == b.gridH)
        val top = maxOf(a.skipTopRows, b.skipTopRows)
        val bottom = minOf(a.gridH - a.skipBottomRows, b.gridH - b.skipBottomRows)
        if (bottom <= top) return 0.0
        var sum = 0L
        var count = 0
        for (row in top until bottom) {
            val base = row * a.gridW
            for (col in 0 until a.gridW) {
                val i = base + col
                sum += kotlin.math.abs((a.luma[i].toInt() and 0xFF) - (b.luma[i].toInt() and 0xFF))
                count++
            }
        }
        if (count == 0) return 0.0
        return 1.0 - (sum.toDouble() / count) / 255.0
    }

    /** Number of distinct moments the screen changed inside [from, to]. */
    private fun motionEvents(frames: List<Frame>, from: Int, to: Int, min: Double): Int {
        var events = 0
        for (k in from until to) {
            if (1.0 - similarity(frames[k], frames[k + 1]) >= min) events++
        }
        return events
    }

    /** Largest dissimilarity between the start frame and any interior frame. */
    private fun departure(frames: List<Frame>, from: Int, to: Int): Double {
        var worst = 0.0
        for (k in from + 1 until to) {
            val d = 1.0 - similarity(frames[from], frames[k])
            if (d > worst) worst = d
        }
        return worst
    }

    /**
     * A span plays as a loop only if the screen goes somewhere and comes
     * back: it departs from the start frame and changes at least twice
     * (once away, once back). One-time changes fail this.
     */
    private fun hasCycleMotion(frames: List<Frame>, from: Int, to: Int, min: Double): Boolean {
        return departure(frames, from, to) >= min && motionEvents(frames, from, to, min) >= 2
    }

    fun find(
        frames: List<Frame>,
        minLoopMs: Long = MIN_LOOP_MS,
        maxLoopMs: Long = 30_000,
        seamThreshold: Double = 0.90,
        motionMin: Double = 0.02,
        maxCandidates: Int = 3
    ): Result {
        if (frames.size < 3) return Result(emptyList(), null)
        val found = ArrayList<Candidate>()
        var best: Candidate? = null
        for (i in 0 until frames.size - 1) {
            val t0 = frames[i].timeMs
            var j = i + 1
            while (j < frames.size && frames[j].timeMs - t0 <= maxLoopMs) {
                val span = frames[j].timeMs - t0
                if (span >= minLoopMs) {
                    val seam = similarity(frames[i], frames[j])
                    val current = best
                    if (current == null || seam > current.seam ||
                        (seam == current.seam && span < current.endMs - current.startMs)
                    ) {
                        best = Candidate(t0, frames[j].timeMs, seam)
                    }
                    if (seam >= seamThreshold &&
                        hasCycleMotion(frames, i, j, motionMin)
                    ) {
                        found.add(Candidate(t0, frames[j].timeMs, seam))
                    }
                }
                j++
            }
        }
        found.sortWith(compareByDescending<Candidate> { it.seam }.thenBy { it.endMs - it.startMs })
        val picked = ArrayList<Candidate>()
        for (c in found) {
            if (picked.size >= maxCandidates) break
            if (picked.none { overlaps(it, c) }) picked.add(c)
        }
        val bestEffort = if (picked.isEmpty() &&
            best != null &&
            hasCycleMotion(
                frames,
                indexOfTime(frames, best.startMs),
                indexOfTime(frames, best.endMs),
                motionMin
            )
        ) {
            best
        } else {
            null
        }
        return Result(picked, bestEffort)
    }

    private fun indexOfTime(frames: List<Frame>, timeMs: Long): Int {
        var best = 0
        var bestDist = Long.MAX_VALUE
        for (i in frames.indices) {
            val d = kotlin.math.abs(frames[i].timeMs - timeMs)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /** True when the spans overlap by more than half the shorter one. */
    private fun overlaps(a: Candidate, b: Candidate): Boolean {
        val shared = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        if (shared <= 0) return false
        val shorter = minOf(a.endMs - a.startMs, b.endMs - b.startMs)
        return shared * 2 > shorter
    }
}
