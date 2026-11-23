package com.vanipy.bidik

import android.content.Context
import android.os.Build

/**
 * User-chosen recording options, kept in SharedPreferences.
 *
 * These exist so a saved clip can contain only the screen it is meant to
 * show, without the app UI at the start or the trip back to stop it:
 *
 * - start delay: after the system consent prompt the recorder waits this
 *   many seconds before the first frame is encoded, giving time to switch
 *   to the screen to share. The countdown shows in the notification,
 *   which also cancels it.
 * - auto-stop: the recording saves itself after this many seconds, so
 *   ending a clip needs no interaction at all. Off means manual stop
 *   (notification or app button), still bounded by
 *   [RecordingStore.MAX_DURATION_MS].
 * - system bars hidden: the encoder crops the status bar and navigation
 *   bar bands out of the video instead of recording them. Cropping
 *   happens inside the capture pipeline (the texture draw samples only
 *   the middle band), so nothing is hidden on the phone itself and no
 *   re-encode is needed. Off keeps the full screen.
 *
 * - app sound: records the phone's internal audio (music and game
 *   sounds from apps that allow capture) as an AAC track in the MP4.
 *   Android 10+ only, rides the screen-recording consent, and requires
 *   the RECORD_AUDIO runtime permission (Android's rule for playback
 *   capture; the microphone is not used by this option). Off means
 *   silent video as before.
 * - mic: additionally records the microphone into the same audio track.
 *   Also needs RECORD_AUDIO. Off by default; nothing records the room
 *   unless the user turns this on.
 * - motion: writes a timestamped accelerometer CSV next to the MP4
 *   (same base name, .motion.csv) for replay/sync, and draws a small
 *   live dot into the video that drifts with tilt and grows with
 *   movement, so viewers see the phone being handled. No permission
 *   needed; the accelerometer is the phone's own sensor.
 * - shake: moves the recorded picture itself with the phone, tuned
 *   for the handycam look of movie footage: a smooth low-frequency
 *   sway as the hand wanders, sharp jolts spread into a slow swell,
 *   and a small zoom pulse on forward/back movement. Caps keep a
 *   resting phone's picture nearly still, and a cover zoom hides the
 *   edges the shifted picture would reveal. Rides the same
 *   accelerometer stream as motion but is an independent toggle: the
 *   dot, the CSV and the shake can be combined freely. No permission
 *   needed either.
 *
 * Defaults: 3s delay (the reason this screen exists), no auto-stop
 * (recordings only end on purpose), bars shown (full-screen output is
 * the established behavior), and every capture option off.
 */
object RecordOptions {
    private const val PREFS = "record_options"
    private const val KEY_DELAY = "delay_sec"
    private const val KEY_AUTOSTOP = "autostop_sec"
    private const val KEY_BARS_HIDDEN = "bars_hidden"
    private const val KEY_SOUND = "app_sound"
    private const val KEY_MIC = "mic"
    private const val KEY_MOTION = "motion_log"
    private const val KEY_SHAKE = "motion_shake"

    val DELAY_CHOICES = intArrayOf(0, 3, 5, 10)
    const val DEFAULT_DELAY_SEC = 3

    val AUTOSTOP_CHOICES = intArrayOf(0, 15, 30, 60, 120, 300)
    const val DEFAULT_AUTOSTOP_SEC = 0

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun delaySeconds(context: Context): Int =
        prefs(context).getInt(KEY_DELAY, DEFAULT_DELAY_SEC)
            .takeIf { it in DELAY_CHOICES } ?: DEFAULT_DELAY_SEC

    fun autoStopSeconds(context: Context): Int =
        prefs(context).getInt(KEY_AUTOSTOP, DEFAULT_AUTOSTOP_SEC)
            .takeIf { it in AUTOSTOP_CHOICES } ?: DEFAULT_AUTOSTOP_SEC

    fun barsHidden(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BARS_HIDDEN, false)

    fun setDelaySeconds(context: Context, seconds: Int) {
        require(seconds in DELAY_CHOICES)
        prefs(context).edit().putInt(KEY_DELAY, seconds).apply()
    }

    fun setAutoStopSeconds(context: Context, seconds: Int) {
        require(seconds in AUTOSTOP_CHOICES)
        prefs(context).edit().putInt(KEY_AUTOSTOP, seconds).apply()
    }

    /** Internal app audio capture needs AudioPlaybackCapture, Android 10+. */
    fun soundSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** App-sound capture; reports off on devices that cannot capture. */
    fun soundEnabled(context: Context): Boolean =
        soundSupported() && prefs(context).getBoolean(KEY_SOUND, false)

    fun setSoundEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, on).apply()
    }

    /** Microphone mixed into the recording's audio track. Default off. */
    fun micEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MIC, false)

    fun setMicEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MIC, on).apply()
    }

    /** Accelerometer sidecar + overlay dot. Default off. */
    fun motionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MOTION, false)

    fun setMotionEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOTION, on).apply()
    }

    /** Picture-shake effect drawn from the accelerometer. Default off. */
    fun shakeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHAKE, false)

    fun setShakeEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHAKE, on).apply()
    }

    fun setBarsHidden(context: Context, hidden: Boolean) {
        prefs(context).edit().putBoolean(KEY_BARS_HIDDEN, hidden).apply()
    }

    /** Moves a value to the next entry in [choices], wrapping around. */
    internal fun nextChoice(choices: IntArray, current: Int): Int {
        val index = choices.indexOf(current)
        return choices[(index + 1) % choices.size]
    }

    fun cycleDelay(context: Context): Int {
        val next = nextChoice(DELAY_CHOICES, delaySeconds(context))
        setDelaySeconds(context, next)
        return next
    }

    fun cycleAutoStop(context: Context): Int {
        val next = nextChoice(AUTOSTOP_CHOICES, autoStopSeconds(context))
        setAutoStopSeconds(context, next)
        return next
    }

    fun delayLabel(seconds: Int): String = if (seconds == 0) "off" else "${seconds}s"

    fun autoStopLabel(seconds: Int): String = when {
        seconds == 0 -> "off"
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60}m"
    }
}
