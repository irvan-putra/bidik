package com.vanipy.bidik

import android.content.Context

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
 * Defaults: 3s delay (the reason this screen exists), no auto-stop
 * (recordings only end on purpose), bars shown (full-screen output is
 * the established behavior).
 */
object RecordOptions {
    private const val PREFS = "record_options"
    private const val KEY_DELAY = "delay_sec"
    private const val KEY_AUTOSTOP = "autostop_sec"
    private const val KEY_BARS_HIDDEN = "bars_hidden"

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
