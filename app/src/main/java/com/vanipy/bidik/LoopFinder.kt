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
 * Candidates are ranked with a seam tolerance: within
 * [RANK_SEAM_EPSILON] of the best seam the tightest single cycle wins,
 * because a two-cycle span can edge out one cycle on codec noise alone
 * (run 44457037709: 3150ms at seam 1.0 over 1400ms at 0.99996), and
 * exporting two cycles plays the motion twice.
 *
 * All scores are motion-weighted: cells that never change during the
 * sampled window play no part in the average (see [activityWeights]).
 * A whole-grid mean dilutes a small animated region - a widget-sized
 * animation is a few cells of a 32x18 grid, so its motion and its seam
 * both vanish under a static screen, and every span scores ~1.0. With
 * the active cells carrying the weight, a small looping region is
 * detected with its real period instead of being missed entirely or
 * matched at an arbitrary span. A static window has no active cells,
 * no weights, and still yields no loop.
 */
object LoopFinder {

    /** Shortest span that can play as a loop, in milliseconds. */
    const val MIN_LOOP_MS: Long = 400

    /** Seam tolerance inside which a shorter span outranks a longer one. */
    const val RANK_SEAM_EPSILON: Double = 0.005

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

    /** Smallest per-step luma change that counts as a cell moving. */
    private const val DENSITY_STEP = 4

    /** Cells moving in fewer steps than this are spikes or codec flicker, not content. */
    private const val DENSITY_MIN_STEPS = 3

    /**
     * Per-cell activity over the whole sequence: in how many steps each
     * cell actually moved (by [DENSITY_STEP] luma units or more), so
     * slow steady motion accumulates instead of vanishing between
     * adjacent samples, and one-off spikes - including codec flicker
     * near video edges - stay below [DENSITY_MIN_STEPS] and get zero
     * weight. Masked rows get zero weight. Survivors are normalized to
     * sum to 1. Returns null when nothing moves (a static window has no
     * loop to find). Uniform density reduces weighted scores to the
     * plain mean.
     */
    fun activityWeights(frames: List<Frame>): DoubleArray? {
        if (frames.size < 2) return null
        val first = frames[0]
        val top = first.skipTopRows
        val bottom = first.gridH - first.skipBottomRows
        if (bottom <= top) return null
        val density = IntArray(first.luma.size)
        for (k in 0 until frames.size - 1) {
            val a = frames[k]
            val b = frames[k + 1]
            for (row in top until bottom) {
                val base = row * first.gridW
                for (col in 0 until first.gridW) {
                    val i = base + col
                    if (kotlin.math.abs(
                            (a.luma[i].toInt() and 0xFF) - (b.luma[i].toInt() and 0xFF)
                        ) >= DENSITY_STEP
                    ) {
                        density[i]++
                    }
                }
            }
        }
        val act = DoubleArray(first.luma.size)
        var total = 0.0
        for (i in act.indices) {
            if (density[i] >= DENSITY_MIN_STEPS) {
                act[i] = density[i].toDouble()
                total += act[i]
            }
        }
        if (total <= 0.0) return null
        for (i in act.indices) act[i] /= total
        return act
    }

    /** Similarity where only the active cells count (see [activityWeights]). */
    fun similarity(a: Frame, b: Frame, weights: DoubleArray): Double {
        require(a.gridW == b.gridW && a.gridH == b.gridH)
        val top = maxOf(a.skipTopRows, b.skipTopRows)
        val bottom = minOf(a.gridH - a.skipBottomRows, b.gridH - b.skipBottomRows)
        if (bottom <= top) return 0.0
        var sum = 0.0
        for (row in top until bottom) {
            val base = row * a.gridW
            for (col in 0 until a.gridW) {
                val i = base + col
                sum += weights[i] * kotlin.math.abs(
                    (a.luma[i].toInt() and 0xFF) - (b.luma[i].toInt() and 0xFF)
                )
            }
        }
        return 1.0 - sum / 255.0
    }

    /**
     * Number of distinct moments the screen changed inside [from, to].
     * Change accumulates from the last event, so slow motion counts at
     * the same total as fast motion: a region drifting below the
     * threshold per step still registers once the accumulated drift
     * crosses it.
     */
    private fun motionEvents(
        frames: List<Frame>, from: Int, to: Int, min: Double, weights: DoubleArray
    ): Int {
        var events = 0
        var last = from
        for (k in from + 1..to) {
            if (1.0 - similarity(frames[last], frames[k], weights) >= min) {
                events++
                last = k
            }
        }
        return events
    }

    /** Largest dissimilarity between the start frame and any interior frame. */
    private fun departure(frames: List<Frame>, from: Int, to: Int, weights: DoubleArray): Double {
        var worst = 0.0
        for (k in from + 1 until to) {
            val d = 1.0 - similarity(frames[from], frames[k], weights)
            if (d > worst) worst = d
        }
        return worst
    }

    /**
     * A span plays as a loop only if the screen goes somewhere and comes
     * back: it departs from the start frame and changes at least twice
     * (once away, once back). One-time changes fail this.
     */
    private fun hasCycleMotion(
        frames: List<Frame>, from: Int, to: Int, min: Double, weights: DoubleArray
    ): Boolean {
        return departure(frames, from, to, weights) >= min &&
            motionEvents(frames, from, to, min, weights) >= 2
    }

    /**
     * A loop's endpoints must match far better than the journey inside:
     * when the seam error is comparable to the deepest departure, the
     * span is a one-way trend (a drift, a scroll), not a return. Slow
     * drifts pass the absolute seam threshold on tolerance alone, so
     * the seam error is capped relative to the departure.
     */
    private fun returnsToStart(
        frames: List<Frame>, from: Int, to: Int, weights: DoubleArray, seam: Double
    ): Boolean {
        val dep = departure(frames, from, to, weights)
        if (dep <= 0.0) return false
        return (1.0 - seam) <= 0.25 * dep
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
        // No motion anywhere means no loop, whatever the seams say.
        val weights = activityWeights(frames) ?: return Result(emptyList(), null)
        val found = ArrayList<Candidate>()
        val foundDeparture = ArrayList<Double>()
        var best: Candidate? = null
        for (i in 0 until frames.size - 1) {
            val t0 = frames[i].timeMs
            var j = i + 1
            while (j < frames.size && frames[j].timeMs - t0 <= maxLoopMs) {
                val span = frames[j].timeMs - t0
                if (span >= minLoopMs) {
                    val seam = similarity(frames[i], frames[j], weights)
                    val current = best
                    if (current == null || seam > current.seam ||
                        (seam == current.seam && span < current.endMs - current.startMs)
                    ) {
                        best = Candidate(t0, frames[j].timeMs, seam)
                    }
                    if (seam >= seamThreshold &&
                        hasCycleMotion(frames, i, j, motionMin, weights) &&
                        returnsToStart(frames, i, j, weights, seam)
                    ) {
                        found.add(Candidate(t0, frames[j].timeMs, seam))
                        foundDeparture.add(departure(frames, i, j, weights))
                    }
                }
                j++
            }
        }
        // Among full matches, prefer spans that cover (nearly) the
        // whole journey: a half-cycle mirrored pair also matches
        // seamlessly, but a loop trimmed to it plays back only part of
        // the motion. The tightest span reaching 80% of the deepest
        // departure wins, then the higher seam.
        val maxDeparture = foundDeparture.maxOrNull() ?: 0.0
        val eligible = ArrayList<Candidate>()
        for (idx in found.indices) {
            if (foundDeparture[idx] >= 0.8 * maxDeparture) eligible.add(found[idx])
        }
        val picked = ArrayList(rankCandidates(eligible, maxCandidates))
        val bestEffort = if (picked.isEmpty() &&
            best != null &&
            hasCycleMotion(
                frames,
                indexOfTime(frames, best.startMs),
                indexOfTime(frames, best.endMs),
                motionMin,
                weights
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
    /**
     * Shared candidate ranking: within [RANK_SEAM_EPSILON] of the best
     * seam the tightest single cycle wins; only a clearly better seam
     * justifies a longer span. Overlapping candidates are dropped after
     * sorting so the list holds distinct spans.
     */
    internal fun rankCandidates(
        candidates: List<Candidate>,
        maxCandidates: Int
    ): List<Candidate> {
        val bestSeam = candidates.maxOfOrNull { it.seam } ?: 0.0
        val sorted = candidates.sortedWith(
            compareBy<Candidate> { if (it.seam >= bestSeam - RANK_SEAM_EPSILON) 0 else 1 }
                .thenBy { it.endMs - it.startMs }
                .thenByDescending { it.seam }
        )
        val picked = ArrayList<Candidate>()
        for (c in sorted) {
            if (picked.size >= maxCandidates) break
            if (picked.none { overlaps(it, c) }) picked.add(c)
        }
        return picked
    }

    private fun overlaps(a: Candidate, b: Candidate): Boolean {
        val shared = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        if (shared <= 0) return false
        val shorter = minOf(a.endMs - a.startMs, b.endMs - b.startMs)
        return shared * 2 > shorter
    }
}
