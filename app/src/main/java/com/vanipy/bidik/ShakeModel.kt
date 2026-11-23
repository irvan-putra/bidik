package com.vanipy.bidik

/**
 * Accelerometer-driven "shake the phone, shake the video" model.
 *
 * Fed by the same sensor stream as the motion dot/CSV (MotionLog), but
 * instead of drawing an indicator it moves the recorded picture itself:
 * the frame slides and rolls a little with the phone, as if the picture
 * were loosely bolted to the screen, and a forward/back jolt nudges the
 * zoom. A resting phone's picture stays still, hard shakes hit tasteful
 * caps, and a small cover zoom hides the edges the shifted picture
 * would otherwise reveal.
 *
 * The reference posture is the recording's own recent pose: the first
 * sample seeds it and a very slow filter re-centers over ~10s, so a
 * sustained new posture relaxes back to a calm picture while quick
 * shakes stay fully visible.
 *
 * Threading mirrors MotionTrace: the sensor thread calls [onSample];
 * the capture thread calls [advanceFrame] once per drawn frame and
 * reads the outputs. All filtering is single-pole so the effect tracks
 * deliberate shakes quickly and settles smoothly. Sign choices (content
 * lags the jolt, content follows the roll) are picked for an inertial
 * feel and are verified for displacement, not for on-device feel.
 */
internal class ShakeModel {
    // Recent-pose estimate of gravity; sensor thread only. Seeded by
    // the first sample so recording start does not jump.
    @Volatile private var restX = 0f
    @Volatile private var restY = 0f
    @Volatile private var restZ = 0f
    private var started = false

    // Smoothed displacement from the recent pose; sensor thread writes,
    // capture thread reads.
    @Volatile private var dispX = 0f
    @Volatile private var dispY = 0f
    @Volatile private var dispZ = 0f

    // Frame-rate lerped outputs; capture thread only.
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set
    var rotationDeg = 0f
        private set
    var scale = 1f
        private set

    /** Sensor thread: one accelerometer sample in m/s^2. */
    fun onSample(x: Float, y: Float, z: Float) {
        if (!started) {
            restX = x
            restY = y
            restZ = z
            started = true
            return
        }
        restX += (x - restX) * REST_ALPHA
        restY += (y - restY) * REST_ALPHA
        restZ += (z - restZ) * REST_ALPHA
        dispX += ((x - restX) - dispX) * DISP_ALPHA
        dispY += ((y - restY) - dispY) * DISP_ALPHA
        dispZ += ((z - restZ) - dispZ) * DISP_ALPHA
    }

    /**
     * Capture thread: advances the lerped outputs one video frame.
     * Targets come from the current displacement; the outputs chase
     * them so the picture eases in and springs back instead of jumping.
     */
    fun advanceFrame() {
        val baseY = if (kotlin.math.abs(restY) > 1f) restY else GRAVITY
        val targetX = (dispX * SHIFT_PER_MS2).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        val targetY = (-dispY * SHIFT_PER_MS2).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        // Co-rotate with the roll: phone top tips right, picture tips
        // right a little. Only a fraction of the roll, capped.
        val rollDeg = Math.toDegrees(
            kotlin.math.atan2(dispX.toDouble(), baseY.toDouble())
        ).toFloat
        val targetRot = (-rollDeg * ROT_GAIN).coerceIn(-MAX_ROT_DEG, MAX_ROT_DEG)
        offsetX += (targetX - offsetX) * FRAME_LERP
        offsetY += (targetY - offsetY) * FRAME_LERP
        rotationDeg += (targetRot - rotationDeg) * FRAME_LERP
        val rad = Math.toRadians(rotationDeg.toDouble())
        val cover = (1f + maxOf(
            kotlin.math.abs(offsetX), kotlin.math.abs(offsetY)
        )) / (kotlin.math.cos(rad) + kotlin.math.sin(rad)).toFloat()
        // Forward/back jolts read as a small zoom pulse; it only ever
        // enlarges the picture, so the cover property is preserved.
        val pulse = (kotlin.math.abs(dispZ) * ZOOM_PER_MS2)
            .coerceIn(0f, MAX_ZOOM_PULSE)
        scale = cover * (1f + pulse) * COVER_MARGIN
    }

    companion object {
        /** Recent-pose filter: ~10s re-centering at a 50Hz sensor. */
        private const val REST_ALPHA = 0.002f

        /** Displacement filter: fast enough to catch a deliberate shake. */
        private const val DISP_ALPHA = 0.25f

        /** NDC shift per m/s^2 of displacement; ~2.6px/X and ~5px/Y on 432x848. */
        private const val SHIFT_PER_MS2 = 0.012f

        /** Picture shift cap: 3.5% of the half-span, ~15px/X and ~30px/Y on 432x848. */
        internal const val MAX_SHIFT = 0.07f

        /** Fraction of phone roll shown as picture roll. */
        private const val ROT_GAIN = 0.12f

        /** Picture roll cap in degrees. */
        internal const val MAX_ROT_DEG = 3f

        /** Per-frame chase rate at the fixed 30fps capture cadence. */
        private const val FRAME_LERP = 0.30f

        /** Zoom pulse per m/s^2 of forward/back jolt. */
        private const val ZOOM_PER_MS2 = 0.004f

        /** Zoom pulse cap. */
        private const val MAX_ZOOM_PULSE = 0.02f

        /** Safety over-scan so rotated corners never peek past the edge. */
        private const val COVER_MARGIN = 1.004f

        private const val GRAVITY = 9.81f
    }
}
