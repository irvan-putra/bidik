package com.vanipy.bidik

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.io.File

/**
 * Optional accelerometer sidecar for a screen recording.
 *
 * The accelerometer is the phone's own sensor, so it works while any
 * app is on screen and needs no permission. Samples (~50Hz, game delay)
 * are written as CSV - t_ms,x,y,z in m/s^2 - with timestamps in
 * milliseconds relative to the video track's first frame, so the log
 * can be replayed in sync with the MP4 it belongs to. The file lands
 * next to the MP4 with the same base name and a .motion.csv suffix.
 *
 * Like the video, the log writes to a partial file in the cache dir and
 * only a cleanly stopped recording keeps it; the caller moves it next
 * to the saved MP4 or deletes it.
 *
 * Each sample also feeds [trace], the small live summary the video
 * overlay draws, so the visible dot and the saved CSV always describe
 * the same motion.
 */
internal class MotionLog(
    context: Context,
    private val partialFile: File,
    private val startNanos: () -> Long
) : SensorEventListener {

    /** Live summary for the video overlay; fed by every sample. */
    internal val trace = MotionTrace()

    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var thread: HandlerThread? = null
    private var writer: java.io.BufferedWriter? = null
    @Volatile private var running = false
    @Volatile private var t0Nanos = -1L
    @Volatile private var samples = 0L

    /** Opens the partial file and starts sampling. False when no sensor. */
    fun start(): Boolean {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        partialFile.delete()
        writer = partialFile.bufferedWriter()
        val ht = HandlerThread("bidik-motion")
        ht.start()
        thread = ht
        running = true
        if (!manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME,
                Handler(ht.looper))) {
            cleanupFile()
            return false
        }
        return true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        trace.onSample(event.values[0], event.values[1], event.values[2])
        if (t0Nanos < 0) {
            val videoStart = startNanos()
            t0Nanos = if (videoStart > 0) videoStart else System.nanoTime()
        }
        val tMs = (System.nanoTime() - t0Nanos) / 1_000_000L
        val w = writer ?: return
        try {
            w.write("$tMs,${event.values[0]},${event.values[1]},${event.values[2]}\n")
            samples++
        } catch (_: Exception) {
            running = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Stops sampling and closes the file. Returns true when [save] was
     * asked for and a non-empty log exists; on false the partial file is
     * deleted.
     */
    fun stop(save: Boolean): Boolean {
        running = false
        manager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        val ok = save && samples > 0 && partialFile.exists() && partialFile.length() > 0
        if (!ok) cleanupFile()
        return ok
    }

    private fun cleanupFile() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        try {
            partialFile.delete()
        } catch (_: Exception) {
        }
    }
}

/**
 * Live motion summary drawn into the recording: a dot that drifts with
 * tilt and grows with movement energy. Smoothed single-pole filters
 * keep it calm; the capture thread reads the volatile fields, the
 * sensor thread writes them.
 */
internal class MotionTrace {
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    @Volatile var energy = 0f

    fun onSample(x: Float, y: Float, z: Float) {
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        energy = 0.8f * energy + 0.2f * kotlin.math.max(0f, magnitude - GRAVITY)
        tiltX = 0.8f * tiltX + 0.2f * x
        tiltY = 0.8f * tiltY + 0.2f * y
    }

    private companion object {
        const val GRAVITY = 9.81f
    }
}
