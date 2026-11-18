package com.vanipy.bidik

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runtime check of the screen recorder on a real (emulator) system: the
 * system consent dialog is answered through UiAutomator, a few seconds of
 * the actual screen are recorded, the recording is stopped from the app's
 * own stop control, and the saved MP4 is probed for codec, size, duration
 * and non-uniform frame content. Evidence (MP4 plus a mid frame) is
 * copied to the app-data dir the CI workflow pulls.
 *
 * The system dialog labels differ across API levels, so the accept and
 * dismiss steps try the known variants and fail with a window dump when
 * none appears.
 */
@RunWith(AndroidJUnit4::class)
class ScreenRecordTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        RecordingStore.dir(context).listFiles()?.forEach { it.delete() }
        RecordingStore.clearActive(context)
    }

    @Test
    fun consentDeniedRecordsNothing() {
        launchMain()
        clickAppButton("Record screen")
        // Dismiss the system consent prompt; labels vary by API level.
        val dismissed = clickFirstSystemOption(30_000, "Don't allow", "Cancel", "CANCEL", "Deny")
        assertTrue("consent dialog never appeared or had no deny button: " + windowDump(), dismissed)
        Thread.sleep(2_000)
        assertTrue("denied consent must not create a recording", RecordingStore.list(context).isEmpty())
        assertNull(RecordingStore.activeName(context))
    }

    @Test
    fun consentedRecordingSavesPlayableVideo() {
        launchMain()
        clickAppButton("Record screen")
        // Prefer whole-screen capture when the picker offers it, then accept.
        clickFirstSystemOption(10_000, "Entire screen")
        val accepted = clickFirstSystemOption(30_000, "Share screen", "Start now", "START NOW")
        assertTrue("consent dialog never appeared or had no accept button: " + windowDump(), accepted)

        // The record button becomes the stop control once the session runs.
        val stop = device.wait(Until.findObject(By.text("Stop recording")), 30_000)
        assertNotNull("stop control never appeared; recording did not start: " + windowDump(), stop)

        Thread.sleep(6_000) // record a few seconds of the real screen
        stop.click()

        val recording = waitForRecording(30_000)
        assertNotNull("no saved recording after stop: " + windowDump(), recording)
        recording!!
        assertTrue("recording suspiciously small: ${recording.length()}", recording.length() > 10_000)
        assertNull("active marker must clear after stop", RecordingStore.activeName(context))

        // Container sanity: ISO BMFF 'ftyp' box near the start.
        val head = ByteArray(12)
        recording.inputStream().use { it.read(head) }
        assertEquals("ftyp", String(head, 4, 4))

        // Probe the video: real dimensions, multi-second duration, and a
        // mid frame that is not a uniform fill (the screen had content).
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(recording.absolutePath)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        assertTrue("no video dimensions in $recording", (width ?: 0) >= 200 && (height ?: 0) >= 200)
        assertTrue("duration ${durationMs}ms shorter than the recorded window", (durationMs ?: 0) >= 3_000)
        val frame: Bitmap? = retriever.getFrameAtTime(1_500_000)
        assertNotNull("no decodable frame at 1.5s", frame)
        assertTrue("mid frame looks uniform (blank capture?)", frameHasContent(frame!!))

        // Evidence for CI pull: the video itself and its mid frame.
        val evidence = context.getExternalFilesDir("recordings-evidence")!!
        evidence.mkdirs()
        evidence.listFiles()?.forEach { it.delete() }
        recording.copyTo(File(evidence, recording.name), overwrite = true)
        File(evidence, "mid-frame.png").outputStream().use {
            frame.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun launchMain() {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)!!.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        assertNotNull(
            "app did not come to the front",
            device.wait(Until.findObject(By.text("Run capture")), 30_000)
        )
    }

    private fun clickAppButton(label: String) {
        val obj = device.wait(Until.findObject(By.text(label)), 15_000)
        assertNotNull("button '$label' not found: " + windowDump(), obj)
        obj!!.click()
    }

    private fun clickFirstSystemOption(timeoutMs: Long, vararg labels: String): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (label in labels) {
                val obj = device.findObject(By.text(label))
                if (obj != null) {
                    obj.click()
                    return true
                }
                // Some dialog buttons are all-caps renderings of mixed-case
                // labels; match case-insensitively through the description.
                val ci = device.findObjects(By.clazz("android.widget.Button"))
                    .firstOrNull { it.text?.equals(label, ignoreCase = true) == true }
                if (ci != null) {
                    ci.click()
                    return true
                }
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun waitForRecording(timeoutMs: Long): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val files = RecordingStore.list(context)
            if (files.isNotEmpty() && files[0].length() > 0) return files[0]
            Thread.sleep(500)
        }
        return null
    }

    private fun frameHasContent(frame: Bitmap): Boolean {
        var min = 255
        var max = 0
        var x = 0
        while (x < frame.width) {
            var y = 0
            while (y < frame.height) {
                val p = frame.getPixel(x, y)
                val luma = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                if (luma < min) min = luma
                if (luma > max) max = luma
                y += 37
            }
            x += 37
        }
        return max - min > 32
    }

    private fun windowDump(): String {
        val dump = File(context.cacheDir, "window-dump.xml")
        return try {
            device.dumpWindowHierarchy(dump)
            dump.readText().take(4_000)
        } catch (e: Exception) {
            "window dump unavailable"
        }
    }
}
