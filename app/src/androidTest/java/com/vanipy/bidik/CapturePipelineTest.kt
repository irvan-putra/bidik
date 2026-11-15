package com.vanipy.bidik

import android.graphics.BitmapFactory
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end pixel check on a real Android runtime (emulator or device).
 *
 * Two gates, deliberately stronger than "not blank" (a blank capture of
 * app chrome or an all-white page is also non-blank):
 *  1. Content recognition: the engine reports a non-empty page title
 *     (proves a real DOM rendered) and the captured pixels carry content
 *     (multiple distinct colors AND at least one clearly dark pixel).
 *  2. Pipeline wiring: the queue file drives a run, result JSON and PNGs
 *     land where the transport expects them.
 *
 * Threading: WebViewCaptureEngine.capture dispatches onto
 * Dispatchers.Main, so the coroutine must be started from a NON-main
 * thread. JUnit runs test bodies on the instrumentation thread, which is
 * fine; wrapping the call in scenario.onActivity { runBlocking { ... } }
 * blocks the main thread and deadlocks the load permanently. Keep
 * onActivity only for grabbing references.
 *
 * Timeouts: every test carries an explicit JUnit timeout so a blocked
 * run fails in minutes instead of hanging the job until the CI timeout.
 *
 * Fixture URL defaults to example.com (stable, deterministic); CI passes
 * the public page under review via instrumentation arg "queue_url" and a
 * human inspects the uploaded artifacts.
 */
@RunWith(AndroidJUnit4::class)
class CapturePipelineTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private fun queueUrl(): String =
        InstrumentationRegistry.getArguments().getString("queue_url")
            ?: "https://example.com/"

    /** Reference grab only - never block or capture inside onActivity. */
    private fun withActivity(block: (MainActivity) -> Unit) {
        activityRule.scenario.onActivity(block)
    }

    @Test(timeout = 180_000)
    fun captureCarriesRecognizablePageContent() = runBlocking {
        lateinit var activity: MainActivity
        lateinit var outDir: File
        withActivity { a ->
            activity = a
            outDir = a.getExternalFilesDir(null)!!.resolve("captures")
            outDir.mkdirs()
        }
        val job = QueueProtocol.PageJob("ci-page", queueUrl(), waitMs = 2000)
        val out = WebViewCaptureEngine(activity).capture(job, outDir)
        assertTrue("empty page title - did a real page render?", out.pageTitle.isNotBlank())

        val bmp = BitmapFactory.decodeFile(out.viewportPng.absolutePath)!!
        val colors = HashSet<Int>()
        var dark = 0
        var samples = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                colors.add(p)
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                if (r + g + b < 600) dark++ // below ~78% luminance
                samples++
                x += bmp.width / 64
            }
            y += bmp.height / 64
        }
        assertTrue("capture near-uniform ($colors colors) - likely blank", colors.size >= 4)
        assertTrue("no dark pixels in $samples samples - likely blank page", dark > 0)
    }

    @Test(timeout = 180_000)
    fun queueFileDrivesRunAndResults() = runBlocking {
        lateinit var activity: MainActivity
        lateinit var outDir: File
        withActivity { a ->
            activity = a
            outDir = a.getExternalFilesDir(null)!!.resolve("captures")
            File(a.filesDir, "capture-queue.json").writeText(
                """{"type":"capture-queue","v":1,"pages":[
                  {"id":"ci-page","url":"${queueUrl()}","mode":"webview","full_page":true,"wait_ms":2000}
                ]}"""
            )
        }
        val outcome = CaptureRunner(activity, LocalQueueTransport(activity.filesDir), outDir).runOnce()
        assertEquals("expected 1 ok, failures: ${outcome.failures}", 1, outcome.ok)
        assertTrue(File(outDir, "ci-page-viewport.png").exists())
        val result = File(activity.filesDir, "last-result.json").readText()
        assertTrue(result.contains("\"capture-result\""))
        assertTrue(result.contains("\"status\":\"ok\""))
    }
}
