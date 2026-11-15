package com.vanipy.bidik

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Pairing/gating guarantees that do NOT need the DeltaChat runtime or any
 * account (the ciEmulator build deliberately ships no transport core):
 *  1. An unpaired cold open must never auto-run captures, even with a
 *     queue file present.
 *  2. The manual Run capture button runs the LOCAL transport only when
 *     unpaired - nothing leaves the device.
 *  3. A malformed pairing QR fails cleanly and leaves the app unpaired.
 * Uses the same loopback fixture pattern as CaptureFixtureTest so no
 * external network is involved.
 */
@RunWith(AndroidJUnit4::class)
class CaptureGatingTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var server: ServerSocket
    private var port: Int = 0

    private val fixtureHtml = """
        <html><head><title>gating-fixture</title></head>
        <body style="background:#ffffff"><h1>gating</h1>
          <div style="width:100px;height:100px;background:#c00000"></div>
        </body></html>
    """.trimIndent()

    private fun queueJson() =
        """{"type":"capture-queue","v":1,"pages":[
          {"id":"gating","url":"http://127.0.0.1:$port/","mode":"webview","full_page":false,"wait_ms":0}
        ]}"""

    private fun runDirs(): List<File> =
        File(ctx.filesDir, "captures").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("run-") } ?: emptyList()

    @Before
    fun cleanStateAndServe() {
        ctx.getSharedPreferences("bidik", Context.MODE_PRIVATE).edit().clear().commit()
        File(ctx.filesDir, "capture-queue.json").delete()
        File(ctx.filesDir, "last-result.json").delete()
        File(ctx.filesDir, "last-summary.txt").delete()
        runDirs().forEach { it.deleteRecursively() }

        UrlGuard.allowLoopbackForTests = true
        server = ServerSocket(0)
        port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        sock.soTimeout = 10_000
                        sock.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val body = fixtureHtml.toByteArray()
                            s.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/html\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray()
                                )
                                write(body)
                                flush()
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    @After
    fun cleanup() {
        UrlGuard.allowLoopbackForTests = false
        server.close()
    }

    @Test(timeout = 60_000)
    fun unpairedColdOpenDoesNotAutoRun() {
        File(ctx.filesDir, "capture-queue.json").writeText(queueJson())
        ActivityScenario.launch(MainActivity::class.java).use {
            // On-open logic is async; give it ample time to misbehave.
            Thread.sleep(4_000)
        }
        assertFalse(
            "unpaired open ran a capture (result file written)",
            File(ctx.filesDir, "last-result.json").exists()
        )
        assertTrue(
            "unpaired open created a run archive: ${runDirs()}",
            runDirs().isEmpty()
        )
    }

    @Test(timeout = 120_000)
    fun manualButtonRunsLocalTransportOnly() {
        File(ctx.filesDir, "capture-queue.json").writeText(queueJson())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var clicked = false
            scenario.onActivity { a ->
                val btn = findButton(a, "Run capture")
                check(btn != null) { "Run capture button not found" }
                btn.performClick()
                clicked = true
            }
            assertTrue(clicked)
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline &&
                !File(ctx.filesDir, "last-result.json").exists()
            ) {
                Thread.sleep(500)
            }
        }
        val result = File(ctx.filesDir, "last-result.json")
        assertTrue("manual run produced no local result", result.exists())
        assertTrue(result.readText().contains("\"status\":\"ok\""))
        assertTrue("manual run produced no run archive", runDirs().isNotEmpty())
    }

    @Test(timeout = 60_000)
    fun malformedPairingQrFailsCleanly() {
        ActivityScenario.launch(MainActivity::class.java).use {
            var threw = false
            try {
                kotlinx.coroutines.runBlocking {
                    SetupController.completePairing(ctx, "not-a-pairing-qr")
                }
            } catch (_: Exception) {
                threw = true
            }
            assertTrue("malformed QR must raise, not silently pair", threw)
            assertFalse(SetupController.isPaired(ctx))
        }
    }

    private fun findButton(a: MainActivity, label: String): android.widget.Button? {
        val root = a.window.decorView.rootView
        val stack = ArrayDeque<android.view.View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeFirst()
            if (v is android.widget.Button && v.text.toString() == label) return v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
            }
        }
        return null
    }
}
