package com.vanipy.mata

import android.graphics.BitmapFactory
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Deterministic capture proof on a real Android runtime WITHOUT any
 * external network dependency: a tiny HTTP server runs on loopback
 * inside the test process and serves a fixed page; the real
 * WebViewCaptureEngine loads, renders and screenshots it, and the full
 * queue -> run -> result pipeline is exercised through CaptureRunner.
 *
 * This complements, never replaces, CapturePipelineTest's real-site
 * integration coverage. The loopback escape (UrlGuard.allowLoopbackForTests)
 * is set only around these tests.
 */
@RunWith(AndroidJUnit4::class)
class CaptureFixtureTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var server: ServerSocket
    private var port: Int = 0

    private val fixtureHtml = """
        <html><head><title>mata-fixture</title></head>
        <body style="background:#ffffff;margin:0">
          <h1 style="color:#111111">mata fixture page</h1>
          <div style="width:200px;height:80px;background:#c00000"></div>
          <div style="width:200px;height:80px;background:#00c000"></div>
          <div style="width:200px;height:80px;background:#0000c0"></div>
        </body></html>
    """.trimIndent()

    @Before
    fun startFixtureServer() {
        server = ServerSocket(0)
        port = server.localPort
        UrlGuard.allowLoopbackForTests = true
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                // Each connection is served on its own daemon thread:
                // Chromium opens speculative connections that may never
                // send a request, and a single sequential accept loop
                // would starve the real request behind one idle socket.
                // The socket timeout bounds how long an idle connection
                // can occupy its thread.
                thread(isDaemon = true) {
                    try {
                        sock.soTimeout = 10_000
                        sock.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            // Consume request headers.
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val body = fixtureHtml.toByteArray()
                            s.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/html; charset=utf-8\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray()
                                )
                                write(body)
                                flush()
                            }
                        }
                    } catch (_: Exception) {
                        // Idle or aborted speculative connection; nothing to serve.
                    }
                }
            }
        }
    }

    @After
    fun stopFixtureServer() {
        UrlGuard.allowLoopbackForTests = false
        server.close()
    }

    private fun withActivity(block: (MainActivity) -> Unit) {
        activityRule.scenario.onActivity(block)
    }

    @Test(timeout = 120_000)
    fun captureFixtureCarriesRecognizableContent() = runBlocking {
        lateinit var activity: MainActivity
        lateinit var outDir: File
        withActivity { a ->
            activity = a
            outDir = a.getExternalFilesDir(null)!!.resolve("captures")
            outDir.mkdirs()
        }
        val job = QueueProtocol.PageJob("ci-fixture", "http://127.0.0.1:$port/", waitMs = 500)
        val out = WebViewCaptureEngine(activity).capture(job, outDir)
        assertEquals("fixture title proves a real DOM rendered", "mata-fixture", out.pageTitle)

        val bmp = BitmapFactory.decodeFile(out.viewportPng.absolutePath)!!
        val colors = HashSet<Int>()
        var dark = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                colors.add(p)
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                if (r + g + b < 600) dark++
                x += bmp.width / 16
            }
            y += bmp.height / 16
        }
        assertTrue("fixture capture near-uniform - likely blank", colors.size >= 4)
        assertTrue("no dark pixels in fixture capture", dark > 0)
    }

    @Test(timeout = 120_000)
    fun queueFileDrivesFixtureRunAndResults() = runBlocking {
        lateinit var activity: MainActivity
        lateinit var outDir: File
        withActivity { a ->
            activity = a
            outDir = a.getExternalFilesDir(null)!!.resolve("captures")
            File(a.filesDir, "capture-queue.json").writeText(
                """{"type":"capture-queue","v":1,"pages":[
                  {"id":"ci-fixture","url":"http://127.0.0.1:$port/","mode":"webview","full_page":false,"wait_ms":500}
                ]}"""
            )
        }
        val outcome = CaptureRunner(activity, LocalQueueTransport(activity.filesDir), outDir).runOnce()
        assertEquals("expected 1 ok, failures: ${outcome.failures}", 1, outcome.ok)
        assertTrue(File(outDir, "ci-fixture-viewport.png").exists())
        val result = File(activity.filesDir, "last-result.json").readText()
        assertTrue(result.contains("\"capture-result\""))
        assertTrue(result.contains("\"status\":\"ok\""))
    }
}
