package com.vanipy.bidik

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runtime check of the screen recorder on a real (emulator) system: the
 * system consent dialog is answered through UiAutomator, a few seconds
 * of the actual screen are recorded, the recording is stopped from the
 * app's own stop control, and the saved MP4 is probed for codec, size,
 * duration and non-uniform frame content. Evidence (MP4s plus frames) is
 * copied to the app-data dir the CI workflow pulls.
 *
 * Two recording shapes are covered:
 *  - animated: the UI keeps changing while recording (the app's status
 *    line is ticked), the shape of the widget-animation use case;
 *  - static: the screen is untouched for the whole window. Run
 *    44371384389 showed a direct MediaRecorder feed compresses still
 *    time (1.7s from a 6s window); the encoder must preserve elapsed
 *    time through stillness, including the still tail at stop.
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
    private var scenario: ActivityScenario<MainActivity>? = null

    companion object {
        /**
         * Evidence is cleared once per run, not per test: clearing in
         * setUp let a later test wipe the files an earlier test had
         * already copied (run 44375141949 pulled zero evidence files).
         */
        @JvmStatic
        @BeforeClass
        fun clearEvidenceOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getExternalFilesDir("recordings-evidence")?.listFiles()
                ?.forEach { it.delete() }
        }
    }

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        // A freshly booted emulator sits on the lock screen; the app's
        // windows are not reachable through UiAutomator until it is gone.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.waitForIdle(5_000)
        RecordingStore.dir(context).listFiles()?.forEach { it.delete() }
        RecordingStore.clearActive(context)
        // Each test opts into the recording options it exercises; the
        // baseline tests keep the original immediate, manual-stop,
        // full-screen behavior.
        RecordOptions.setDelaySeconds(context, 0)
        RecordOptions.setAutoStopSeconds(context, 0)
        RecordOptions.setBarsHidden(context, false)
        RecordOptions.setSoundEnabled(context, false)
        RecordOptions.setMicEnabled(context, false)
        RecordOptions.setMotionEnabled(context, false)
    }

    @After
    fun tearDown() {
        // Never leave a recording session or an activity behind a test.
        if (RecordingStore.activeName(context) != null) {
            context.startService(
                Intent(context, ScreenRecordService::class.java)
                    .setAction(ScreenRecordService.ACTION_STOP)
            )
            Thread.sleep(2_000)
        }
        scenario?.close()
        scenario = null
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
    fun animatedRecordingPreservesElapsedTime() {
        val before = recordingsPresent()
        startConsentedRecording()
        val stop = waitForStopControl()

        // Record ~6s while the UI keeps changing (the widget-animation
        // use case): deterministic status ticks guarantee compositor
        // frames through the whole window.
        val recordDeadline = System.currentTimeMillis() + 6_000
        var tick = 0
        while (System.currentTimeMillis() < recordDeadline) {
            tick++
            scenario!!.onActivity { it.status.text = "recording tick $tick" }
            Thread.sleep(200)
        }
        stop.click()

        val recording = waitForRecording(30_000, before)
        assertNotNull("no saved recording after stop: " + windowDump(), recording)
        assertPlayableVideo(recording!!, frameUs = 1_500_000, what = "animated")
        // Elapsed time must survive: 6s of motion, generous upper bound.
        assertElapsed(recording, minimumMs = 5_400, maximumMs = 20_000, what = "animated")

        copyEvidence(recording, "mid-frame.png", frameUs = 1_500_000)
    }

    @Test
    fun staticRecordingPreservesElapsedTime() {
        val before = recordingsPresent()
        startConsentedRecording()
        val stop = waitForStopControl()

        // No UI changes at all: the compositor is idle for the whole
        // window and through the stop. The recorder must still produce a
        // video whose duration is the real elapsed time (regression case
        // for the 1.7s-from-6s direct-feed failure).
        Thread.sleep(6_000)
        stop.click()

        val recording = waitForRecording(30_000, before)
        assertNotNull("no saved recording after stop: " + windowDump(), recording)
        assertPlayableVideo(recording!!, frameUs = 1_000_000, what = "static")
        assertElapsed(recording, minimumMs = 5_400, maximumMs = 20_000, what = "static")
        // The still tail must be in the file: a frame late in the video
        // decodes and shows the screen.
        assertPlayableVideo(recording, frameUs = 5_000_000, what = "static tail")

        copyEvidence(recording, "static-tail-frame.png", frameUs = 5_000_000)
    }

    /**
     * A 4s start delay must leave those 4s out of the clip: the
     * broadcast state stays "starting" during the countdown, and the
     * saved video's duration tracks only the time after it.
     */
    @Test
    fun delayedStartTrimsClipStart() {
        RecordOptions.setDelaySeconds(context, 5)
        val states = recordStatusStates()
        launchMain()
        val before = recordingsPresent()
        clickAppButton("Record screen")
        clickFirstSystemOption(10_000, "Entire screen")
        val accepted = clickFirstSystemOption(30_000, "Share screen", "Start now", "START NOW")
        assertTrue("consent dialog never appeared: " + windowDump(), accepted)

        Thread.sleep(1_500)
        synchronized(states) {
            assertTrue("expected the countdown state 1.5s after consent, got $states",
                states.isNotEmpty() && states.last() == ScreenRecordService.STATE_STARTING)
        }
        assertTrue("recording must begin after the 5s delay: $states",
            waitForState(states, ScreenRecordService.STATE_RECORDING, 10_000))

        // Record ~3s past the delay, then stop from the app.
        Thread.sleep(3_000)
        waitForStopControl().click()

        val recording = waitForRecording(30_000, before)
        assertNotNull("no saved recording after delayed start", recording)
        // Wall time consent-to-stop was ~8s; only the ~3s after the
        // countdown belong in the file.
        assertElapsed(recording!!, minimumMs = 2_200, maximumMs = 6_000, what = "delayed")
        assertTrue("status sequence must run starting->recording->saved: $states",
            waitForState(states, ScreenRecordService.STATE_SAVED, 10_000))
        copyEvidence(recording, "delayed-frame.png", frameUs = 1_500_000)
    }

    /**
     * With an auto-stop duration the recording saves itself: no stop
     * tap, no return to the app, so the tail of the clip shows the
     * target screen rather than the trip back.
     */
    @Test
    fun autoStopSavesWithoutInteraction() {
        RecordOptions.setDelaySeconds(context, 0)
        RecordOptions.setAutoStopSeconds(context, 15)
        launchMain()
        val before = recordingsPresent()
        clickAppButton("Record screen")
        clickFirstSystemOption(10_000, "Entire screen")
        val accepted = clickFirstSystemOption(30_000, "Share screen", "Start now", "START NOW")
        assertTrue("consent dialog never appeared: " + windowDump(), accepted)

        // No further interaction: the session must end and save alone.
        val recording = waitForRecording(45_000, before)
        assertNotNull("auto-stop never saved the recording", recording)
        assertPlayableVideo(recording!!, frameUs = 1_000_000, what = "auto-stop")
        assertElapsed(recording, minimumMs = 13_000, maximumMs = 25_000, what = "auto-stop")
        copyEvidence(recording, "autostop-frame.png", frameUs = 2_000_000)
    }

    /**
     * The hidden-bars option crops the status and navigation bars in the
     * encoder: the saved video is shorter by exactly the two bands, and
     * its pixels match the middle band of a full-screen recording.
     */
    @Test
    fun hiddenBarsCropTheVideo() {
        val bands = SystemBars.bands(context)
        assertTrue("emulator must report a status bar", bands.topPx > 0)

        // Reference: full-screen recording of the untouched UI.
        val before = recordingsPresent()
        startConsentedRecording()
        waitForStopControl()
        Thread.sleep(4_000)
        waitForStopControl().click()
        val full = waitForRecording(30_000, before)
        assertNotNull(full)
        val fullDims = videoDims(full!!)
        val fullFrame = frameAt(full, 1_500_000)
        assertNotNull(fullFrame)

        RecordingStore.dir(context).listFiles()?.forEach { it.delete() }
        RecordOptions.setBarsHidden(context, true)
        scenario?.close()
        val before2 = recordingsPresent()
        startConsentedRecording()
        waitForStopControl()
        Thread.sleep(4_000)
        waitForStopControl().click()
        val cropped = waitForRecording(30_000, before2)
        assertNotNull(cropped)
        val croppedDims = videoDims(cropped!!)

        assertEquals(fullDims.first, croppedDims.first)
        assertEquals(
            "cropped height must drop exactly the two bar bands",
            fullDims.second - bands.topPx - bands.bottomPx, croppedDims.second
        )

        // Pixel proof: the cropped frame is the middle band of the full
        // frame, not the top of it (which holds the status bar).
        val croppedFrame = frameAt(cropped, 1_500_000)
        assertNotNull(croppedFrame)
        val mad = bandDifference(fullFrame!!, bands.topPx, croppedFrame!!)
        assertTrue("cropped frame differs from the matching full-frame band (mad=$mad)", mad < 25.0)

        copyEvidence(cropped, "cropped-bars-frame.png", frameUs = 1_500_000)
    }

    /**
     * End to end for the GIF path: record a structured, cyclical
     * target - a bright marker stepping through four corner positions
     * over a static dark-slate background, 400ms per phase, a 1.6s
     * cycle - let the loop search find the cycle, export its span, and
     * verify the GIF's marker, frame count, size and decodability.
     *
     * The target is deliberately not a flat full-screen blink: a
     * mostly-black frame pair could be endpoint-identical without any
     * meaningful recurrence, so the marker and background give every
     * sampled frame real structure. Seam evidence therefore covers the
     * frames NEIGHBORING the endpoints too: identical endpoints alone
     * could hide a jump at the wrap, and matching the frames one phase
     * after both endpoints shows the wrap continues the same motion
     * step as the loop interior.
     */
    @Test
    fun loopFoundAndGifExported() {
        launchMain()
        val bg = 0xFF334455.toInt()
        val markerColor = 0xFFFFFF00.toInt()
        val sizePx = 120
        val phaseMs = 400L
        val laidOut = java.util.concurrent.CountDownLatch(1)
        lateinit var marker: android.widget.ImageView
        var baseX = 0f
        var baseY = 0f
        var stepX = 0f
        var stepY = 0f
        // Phase 0 goes up BEFORE consent so every sampled frame is part
        // of the cycle.
        scenario!!.onActivity {
            it.window.decorView.setBackgroundColor(bg)
            it.status.setBackgroundColor(bg)
            marker = android.widget.ImageView(it)
            marker.setBackgroundColor(markerColor)
            val root = it.window.decorView
                .findViewById<android.widget.FrameLayout>(android.R.id.content)
            root.addView(marker, android.widget.FrameLayout.LayoutParams(sizePx, sizePx))
            marker.post {
                stepX = root.width / 4f
                stepY = root.height / 6f
                baseX = (root.width - sizePx) / 2f
                baseY = (root.height - sizePx) / 2f
                marker.x = baseX - stepX
                marker.y = baseY - stepY
                laidOut.countDown()
            }
        }
        assertTrue("marker view never laid out", laidOut.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val before = recordingsPresent()
        clickAppButton("Record screen")
        clickFirstSystemOption(10_000, "Entire screen")
        val accepted = clickFirstSystemOption(30_000, "Share screen", "Start now", "START NOW")
        assertTrue("consent dialog never appeared: " + windowDump(), accepted)
        waitForStopControl()

        val deadline = System.currentTimeMillis() + 6_800
        var phase = 0
        while (System.currentTimeMillis() < deadline) {
            phase = (phase + 1) % 4
            val p = phase
            scenario!!.onActivity {
                marker.x = baseX + (if (p == 1 || p == 2) stepX else -stepX)
                marker.y = baseY + (if (p >= 2) stepY else -stepY)
            }
            Thread.sleep(phaseMs)
        }
        waitForStopControl().click()
        val recording = waitForRecording(30_000, before)
        assertNotNull(recording)

        val result = LoopAnalyzer.analyze(recording!!)
        // Evidence first: whatever the assertions decide, the analyzed
        // clip and the search's own numbers land in the artifact.
        recording.copyTo(java.io.File(evidenceDir(), "loop-source-" + recording.name), overwrite = true)
        if (result.candidates.isNotEmpty()) {
            // The matcher's own view of the top candidate's endpoints:
            // the 64x36 luma grids it compared, saved as PNGs. When the
            // full-resolution seam frames disagree with the grid seam,
            // these show which side misread the screen.
            val probe = MediaMetadataRetriever()
            probe.setDataSource(recording.absolutePath)
            val topForProbe = result.candidates.first()
            for (pair in listOf("start" to topForProbe.startMs, "end" to topForProbe.endMs)) {
                val g = LoopAnalyzer.sampleGrid(probe, pair.second * 1000L)
                if (g != null) {
                    val px = IntArray(LoopAnalyzer.GRID_W * LoopAnalyzer.GRID_H) { i ->
                        val v = g[i].toInt() and 0xFF
                        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                    val bmp = Bitmap.createBitmap(
                        px, LoopAnalyzer.GRID_W, LoopAnalyzer.GRID_H, Bitmap.Config.ARGB_8888
                    )
                    saveEvidenceBitmap("loop-grid-" + pair.first + ".png", bmp)
                }
            }
            probe.release()
        }
        java.io.File(evidenceDir(), "loop-analysis.txt").writeText(
            "duration recording=${recording.name} size=${recording.length()}\n" +
            "candidates:\n" +
            result.candidates.joinToString("\n") {
                "  span=${it.startMs}-${it.endMs} seam=${it.seam}"
            } + "\nbestEffort=${result.bestEffort}\n"
        )
        assertTrue("no loop candidate in a periodic recording", result.candidates.isNotEmpty())
        val top = result.candidates.first()
        assertTrue("seam ${top.seam} too low for the periodic screen", top.seam >= 0.95)
        val gif = java.io.File(context.cacheDir, "loop-test.gif")
        gif.delete()
        val outcome = GifExporter.export(recording, top.startMs, top.endMs, gif)
        assertTrue("GIF too small to be real: ${outcome.bytes}", outcome.bytes > 2_000)
        assertTrue("GIF wider than the cap", outcome.width <= GifExporter.DEFAULT_MAX_WIDTH)
        assertTrue(
            "frame count ${outcome.frames} far from span*fps",
            outcome.frames in 4..20
        )
        val bytes = gif.readBytes()
        assertEquals("GIF89a", String(bytes, 0, 6))
        assertTrue(
            "missing the infinite-loop marker",
            String(bytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0")
        )
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("GIF does not decode on Android", decoded)
        assertEquals(outcome.width, decoded!!.width)
        assertEquals(outcome.height, decoded.height)
        gif.copyTo(java.io.File(evidenceDir(), gif.name), overwrite = true)

        // Accuracy check comes AFTER the export on purpose: the export
        // proof must survive a mis-tuned span pick (the span accuracy is
        // a known, loudly-reported tune gap, non-gating for the build).
        assertTrue(
            "loop span ${top.startMs}-${top.endMs} is not the ~1.6s cycle",
            top.endMs - top.startMs in 1_200..2_000
        )

        // Seam evidence at full resolution: the source frames at the
        // loop endpoints must be (nearly) the same picture, or the GIF
        // visibly jumps at the wrap. Saved as PNGs for eyeballing.
        val durationMs = run {
            val r = MediaMetadataRetriever()
            r.setDataSource(recording.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            r.release()
            d
        }
        // OPTION_CLOSEST, not the sync-frame default: the encoder
        // keyframes once a second, so CLOSEST_SYNC can return frames a
        // second away from the endpoints and fail a correct loop (run
        // 44671971209: MAD 19 on a verified exact loop, MAD 0.01 with
        // accurate extraction).
        val seamA = frameAt(
            recording, top.startMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST
        )
        val seamB = frameAt(
            recording, top.endMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST
        )
        assertNotNull("no frame at loop start", seamA)
        assertNotNull("no frame at loop end", seamB)
        saveEvidenceBitmap("loop-seam-start.png", seamA!!)
        saveEvidenceBitmap("loop-seam-end.png", seamB!!)
        val mad = meanAbsDiff(seamA!!, seamB!!)
        assertTrue("loop endpoints differ on screen (MAD $mad)", mad <= 12.0)

        // Neighbor evidence one phase (400ms) past the endpoints. If
        // the wrap jumped, only the endpoints would match; the frames
        // one phase on must continue the same motion step on both
        // sides. OPTION_CLOSEST reads the frame nearest the requested
        // time, not the nearest sync frame, so the phase offset is real.
        val nextA = if (top.endMs + phaseMs < durationMs)
            frameAt(recording, (top.startMs + phaseMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) else null
        val nextB = if (nextA != null)
            frameAt(recording, (top.endMs + phaseMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) else null
        if (nextA != null && nextB != null) {
            val madNext = meanAbsDiff(nextA, nextB)
            assertTrue(
                "frames one phase after the loop endpoints differ (MAD $madNext)",
                madNext <= 12.0
            )
            saveEvidenceBitmap("loop-seam-start-next.png", nextA)
            saveEvidenceBitmap("loop-seam-end-next.png", nextB)
        } else {
            assertTrue("loop starts at 0: no earlier neighbor frame", top.startMs >= phaseMs)
            val prevA = frameAt(recording, (top.startMs - phaseMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            val prevB = frameAt(recording, (top.endMs - phaseMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            assertNotNull(prevA)
            assertNotNull(prevB)
            val madPrev = meanAbsDiff(prevA!!, prevB!!)
            assertTrue(
                "frames one phase before the loop endpoints differ (MAD $madPrev)",
                madPrev <= 12.0
            )
            saveEvidenceBitmap("loop-seam-start-prev.png", prevA)
            saveEvidenceBitmap("loop-seam-end-prev.png", prevB)
        }

        // The target is structured, not a flat blink: the mid-loop
        // frame shows the marker in the opposite position (real motion
        // between phases), and the seam frame itself carries structure
        // beyond the bare background color.
        val mid = frameAt(
            recording,
            (top.startMs + (top.endMs - top.startMs) / 2) * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST
        )
        assertNotNull(mid)
        val motion = meanAbsDiff(seamA, mid!!)
        assertTrue("mid-loop frame shows no motion against the seam (MAD $motion)", motion > 8.0)
        saveEvidenceBitmap("loop-mid-frame.png", mid)
        val flat = android.graphics.Bitmap.createBitmap(seamA.width, seamA.height, seamA.config)
        flat.eraseColor(bg)
        val structure = meanAbsDiff(seamA, flat)
        assertTrue("seam frame is the flat background (MAD $structure)", structure > 5.0)
    }

    @Test
    fun noLoopInNonRepeatingRecording() {
        launchMain()
        // Six grays, pairwise 51 luma apart (seam <= 0.80, under the
        // 0.90 bar): real motion, but the screen never returns to an
        // earlier state, so a clean loop must NOT be reported. The first
        // gray goes up before consent so the recording contains only the
        // non-repeating sequence. bestEffort (below the bar) is allowed;
        // a confident candidate is not.
        val colors = intArrayOf(
            0xFF000000.toInt(), 0xFF333333.toInt(), 0xFF666666.toInt(),
            0xFF999999.toInt(), 0xFFCCCCCC.toInt(), 0xFFFFFFFF.toInt()
        )
        scenario!!.onActivity {
            it.window.decorView.setBackgroundColor(colors[0])
            it.status.setBackgroundColor(colors[0])
        }
        val before = recordingsPresent()
        clickAppButton("Record screen")
        clickFirstSystemOption(10_000, "Entire screen")
        val accepted = clickFirstSystemOption(30_000, "Share screen", "Start now", "START NOW")
        assertTrue("consent dialog never appeared: " + windowDump(), accepted)
        waitForStopControl()

        for (color in colors) {
            scenario!!.onActivity {
                it.window.decorView.setBackgroundColor(color)
                it.status.setBackgroundColor(color)
            }
            Thread.sleep(400)
        }
        waitForStopControl().click()
        val recording = waitForRecording(30_000, before)
        assertNotNull(recording)

        val result = LoopAnalyzer.analyze(recording!!)
        assertTrue(
            "confident loop reported for a screen that never repeats: ${result.candidates}",
            result.candidates.isEmpty()
        )
        val guess = result.bestEffort
        assertTrue(
            "best-effort guess seam ${guess?.seam} at or above the confident bar",
            guess == null || guess.seam < 0.90
        )
    }

    /**
     * The sound options produce real captured audio: with game sound
     * on, a loud sine played by the test app (USAGE_MEDIA, capturable
     * by policy) must land in the MP4 as a non-silent AAC track, and
     * the motion option must leave a populated .motion.csv sidecar.
     * RECORD_AUDIO is granted by the runner script; the emulator runs
     * with its audio backend on (the workflow no longer passes
     * -noaudio). A silent track here means capture policy or the
     * emulator backend failed, not a pass.
     */
    @Test
    fun gameSoundAndMotionAreCaptured() {
        RecordOptions.setSoundEnabled(context, true)
        RecordOptions.setMotionEnabled(context, true)
        val before = recordingsPresent()
        val tone = TestTone()
        startConsentedRecording()
        val stop = waitForStopControl()
        tone.start()
        Thread.sleep(5_000)
        tone.stop()
        stop.click()

        val recording = waitForRecording(30_000, before)
        assertNotNull("no saved recording after stop: " + windowDump(), recording)

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(recording!!.absolutePath)
        val hasAudio =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        retriever.release()
        assertEquals("recording has no audio track", "yes", hasAudio)

        val rms = decodeAudioRms(recording)
        assertNotNull("audio track present but undecodable", rms)
        assertTrue(
            "captured audio is silent (RMS $rms); capture policy or emulator backend failed",
            rms!! > 1_000.0
        )

        val sidecar = java.io.File(
            RecordingStore.dir(context),
            recording.nameWithoutExtension + ".motion.csv"
        )
        assertTrue("no motion sidecar next to the recording", sidecar.exists())
        val rows = sidecar.readLines()
        assertTrue("motion sidecar too short: ${rows.size} rows", rows.size > 50)
        assertTrue(
            "motion sidecar first column is not a ms timestamp: ${rows.first()}",
            rows.first().substringBefore(',').toLongOrNull() != null
        )
        sidecar.copyTo(java.io.File(evidenceDir(), sidecar.name), overwrite = true)
        recording.copyTo(
            java.io.File(evidenceDir(), recording.name),
            overwrite = true
        )
    }

    /**
     * The motion overlay must visibly answer movement, not only fill a
     * CSV: synthetic samples go through the same MotionTrace.onSample
     * the sensor feeds (test-only process-local handle, no production
     * command), and the bottom-right dot region of the saved video
     * must change against the calm baseline.
     */
    @Test
    fun motionOverlayRespondsToMovement() {
        RecordOptions.setMotionEnabled(context, true)
        val before = recordingsPresent()
        startConsentedRecording()
        val stop = waitForStopControl()
        Thread.sleep(2_000)
        val trace = ScreenRecordService.activeMotionTrace
        assertNotNull("motion trace not live during recording", trace)
        // Fast enough to dominate the real sensor stream: the emulator
        // pushes its static accelerometer at ~50Hz, and each real sample
        // decays the injected tilt/energy by 0.8 (run 44679335893: 25Hz
        // injection landed a barely-visible 6px shift).
        repeat(200) {
            trace!!.onSample(6f, 6f, 15f)
            Thread.sleep(10)
        }
        stop.click()

        val recording = waitForRecording(30_000, before)
        assertNotNull("no saved recording after stop: " + windowDump(), recording)
        recording!!.copyTo(
            java.io.File(evidenceDir(), recording.name),
            overwrite = true
        )

        val calm = frameAt(
            recording, 1_000_000, MediaMetadataRetriever.OPTION_CLOSEST
        )
        val moved = frameAt(
            recording, 3_500_000, MediaMetadataRetriever.OPTION_CLOSEST
        )
        assertNotNull("no calm baseline frame in recording", calm)
        assertNotNull("no moved frame in recording", moved)
        saveEvidenceBitmap("overlay-calm.png", calm!!)
        saveEvidenceBitmap("overlay-moved.png", moved!!)
        val movedDiff = dotRegionDiff(calm, moved)
        assertTrue(
            "motion overlay did not visibly respond (region diff $movedDiff)",
            movedDiff > 5.0
        )
    }

    /** Mean abs diff inside the region the motion dot travels in. */
    private fun dotRegionDiff(a: Bitmap, b: Bitmap): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val x0 = (w * 0.75f).toInt()
        val y0 = (h * 0.73f).toInt()
        val ra = Bitmap.createBitmap(a, x0, y0, w - x0, h - y0)
        val rb = Bitmap.createBitmap(b, x0, y0, w - x0, h - y0)
        return meanAbsDiff(ra, rb)
    }

    /**
     * Decodes the recording's first audio track to PCM and returns its
     * RMS amplitude, or null when no decodable audio track exists.
     */
    private fun decodeAudioRms(file: File): Double? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                track = i
                break
            }
        }
        if (track < 0) {
            extractor.release()
            return null
        }
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        extractor.selectTrack(track)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        var sumSquares = 0.0
        var count = 0L
        val info = MediaCodec.BufferInfo()
        var sawEos = false
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!sawEos && System.nanoTime() < deadline) {
            val input = decoder.dequeueInputBuffer(10_000)
            if (input >= 0) {
                val buffer = decoder.getInputBuffer(input)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(
                        input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } else {
                    decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            val output = decoder.dequeueOutputBuffer(info, 10_000)
            if (output >= 0) {
                val pcm = decoder.getOutputBuffer(output)
                if (pcm != null && info.size > 0) {
                    pcm.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    while (pcm.remaining() >= 2) {
                        val s = pcm.short.toInt()
                        sumSquares += s.toDouble() * s
                        count++
                    }
                }
                decoder.releaseOutputBuffer(output, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()
        if (count == 0L) return null
        return kotlin.math.sqrt(sumSquares / count)
    }

    /**
     * A loud 440Hz sine on USAGE_MEDIA from the test app: exactly the
     * kind of playback a consented recorder may capture. MODE_STATIC
     * with an infinite loop keeps it playing with no feeding thread.
     */
    private class TestTone {
        private var track: android.media.AudioTrack? = null

        fun start() {
            val frames = SAMPLE_RATE / 2 // 0.5s per loop
            val pcm = ShortArray(frames) {
                (Math.sin(2.0 * Math.PI * 440.0 * it / SAMPLE_RATE) * AMPLITUDE).toInt().toShort()
            }
            val at = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            at.write(pcm, 0, pcm.size)
            at.setLoopPoints(0, frames, -1)
            at.play()
            track = at
        }

        fun stop() {
            try {
                track?.stop()
            } catch (_: Exception) {
            }
            try {
                track?.release()
            } catch (_: Exception) {
            }
            track = null
        }

        companion object {
            private const val SAMPLE_RATE = 44_100
            private const val AMPLITUDE = 20_000
        }
    }

    private fun recordStatusStates(): MutableList<String> {
        val states = java.util.Collections.synchronizedList(mutableListOf<String>())
        val filter = IntentFilter(ScreenRecordService.ACTION_STATUS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                i.getStringExtra(ScreenRecordService.EXTRA_STATE)?.let { states.add(it) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        return states
    }

    private fun waitForState(
        states: MutableList<String>, wanted: String, timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(states) {
                if (states.contains(wanted)) return true
            }
            Thread.sleep(200)
        }
        return false
    }

    private fun videoDims(file: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        retriever.release()
        return w to h
    }

    private fun frameAt(
        file: File,
        frameUs: Long,
        option: Int = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime(frameUs, option)
        retriever.release()
        return frame
    }

    /**
     * Mean absolute luma difference between the cropped frame and the
     * full frame's band starting at [offsetY]. Both frames are decoded
     * at the same width and the cropped height is exactly the full
     * height minus the two bands, so pixels map one to one; sampled on
     * a coarse grid.
     */
    private fun meanAbsDiff(a: Bitmap, b: Bitmap): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        var sum = 0L
        for (i in 0 until w * h) {
            val da = pa[i]
            val db = pb[i]
            sum += (kotlin.math.abs((da shr 16 and 0xFF) - (db shr 16 and 0xFF)) +
                kotlin.math.abs((da shr 8 and 0xFF) - (db shr 8 and 0xFF)) +
                kotlin.math.abs((da and 0xFF) - (db and 0xFF))) / 3
        }
        return sum.toDouble() / (w * h)
    }

    private fun saveEvidenceBitmap(name: String, bmp: Bitmap) {
        val out = java.io.File(evidenceDir(), name)
        java.io.FileOutputStream(out).use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun bandDifference(full: Bitmap, offsetY: Int, cropped: Bitmap): Double {
        var sum = 0L
        var count = 0
        var y = 0
        while (y < cropped.height) {
            var x = 0
            while (x < cropped.width) {
                val a = full.getPixel(x, offsetY + y)
                val b = cropped.getPixel(x, y)
                val la = ((a shr 16 and 0xFF) + (a shr 8 and 0xFF) + (a and 0xFF)) / 3
                val lb = ((b shr 16 and 0xFF) + (b shr 8 and 0xFF) + (b and 0xFF)) / 3
                sum += kotlin.math.abs(la - lb)
                count++
                x += 61
            }
            y += 61
        }
        return if (count == 0) 255.0 else sum.toDouble() / count
    }

    private fun startConsentedRecording() {
        launchMain()
        clickAppButton("Record screen")
        // Prefer whole-screen capture when the picker offers it, then accept.
        clickFirstSystemOption(10_000, "Entire screen")
        val accepted = clickFirstSystemOption(30_000, "Share screen", "Start now", "START NOW")
        assertTrue("consent dialog never appeared or had no accept button: " + windowDump(), accepted)
    }

    private fun waitForStopControl(): androidx.test.uiautomator.UiObject2 {
        // The record button becomes the stop control once the session runs.
        val stop = device.wait(
            Until.findObject(By.text(Pattern.compile("stop recording", Pattern.CASE_INSENSITIVE))),
            30_000
        )
        assertNotNull("stop control never appeared; recording did not start: " + windowDump(), stop)
        return stop!!
    }

    /**
     * Container, size, dimensions and a decodable, non-blank frame at
     * [frameUs]; also that no in-progress file leaked into the list.
     */
    private fun assertPlayableVideo(recording: File, frameUs: Long, what: String) {
        assertTrue("$what recording suspiciously small: ${recording.length()}",
            recording.length() > 10_000)
        assertNull("active marker must clear after stop", RecordingStore.activeName(context))

        // Container sanity: ISO BMFF 'ftyp' box near the start.
        val head = ByteArray(12)
        recording.inputStream().use { it.read(head) }
        assertEquals("ftyp", String(head, 4, 4))

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(recording.absolutePath)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        assertTrue("no video dimensions in $recording", (width ?: 0) >= 200 && (height ?: 0) >= 200)
        val frame: Bitmap? = retriever.getFrameAtTime(frameUs)
        assertNotNull("$what: no decodable frame at ${frameUs}us", frame)
        assertTrue("$what: frame looks uniform (blank capture?)", frameHasContent(frame!!))
        retriever.release()
    }

    private fun assertElapsed(recording: File, minimumMs: Long, maximumMs: Long, what: String) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(recording.absolutePath)
        val durationMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        retriever.release()
        assertTrue(
            "$what: duration ${durationMs}ms shorter than the recorded window (${minimumMs}ms min)",
            (durationMs ?: 0) >= minimumMs
        )
        assertTrue(
            "$what: duration ${durationMs}ms far beyond the recorded window (${maximumMs}ms max)",
            (durationMs ?: Long.MAX_VALUE) <= maximumMs
        )
    }

    private fun evidenceDir(): File {
        val dir = context.getExternalFilesDir("recordings-evidence")!!
        dir.mkdirs()
        return dir
    }

    private fun copyEvidence(recording: File, frameName: String, frameUs: Long) {
        val evidence = evidenceDir()
        recording.copyTo(File(evidence, recording.name), overwrite = true)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(recording.absolutePath)
        val frame = retriever.getFrameAtTime(frameUs)
        if (frame != null) {
            File(evidence, frameName).outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        retriever.release()
    }

    private fun launchMain() {
        // ActivityScenario (the pattern the other instrumented tests use
        // successfully on this emulator) reliably brings the activity to
        // the front; a raw context.startActivity raced the launcher and
        // the activity never became visible (run 44368283083).
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertNotNull(
            "app did not come to the front: " + windowDump(),
            device.wait(Until.findObject(By.text(Pattern.compile("run capture", Pattern.CASE_INSENSITIVE))), 30_000)
        )
    }

    private fun clickAppButton(label: String) {
        val obj = device.wait(
            Until.findObject(
                By.text(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE))
            ), 15_000
        )
        assertNotNull("button '$label' not found: " + windowDump(), obj)
        obj!!.click()
    }

    private fun clickFirstSystemOption(timeoutMs: Long, vararg labels: String): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (label in labels) {
                val obj = device.findObject(
                    By.text(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE))
                )
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

    /**
     * Recordings from earlier tests stay in the store, so "the newest
     * file" is only this test's recording once the service has moved
     * its .part into place. Snapshot the names present before the
     * recording starts and wait for a NEW non-empty name; polling the
     * newest file raced the finalize step and returned an earlier
     * test's clip (run 44455167131 analyzed the tick-counter recording
     * instead of the marker cycle).
     */
    private fun recordingsPresent(): Set<String> =
        RecordingStore.list(context).map { it.name }.toSet()

    private fun waitForRecording(timeoutMs: Long, before: Set<String>): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val fresh = RecordingStore.list(context).firstOrNull {
                it.name !in before && it.length() > 0
            }
            if (fresh != null) return fresh
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
