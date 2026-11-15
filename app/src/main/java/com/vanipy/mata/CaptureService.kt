package com.vanipy.mata

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Runs a capture pass over the current queue: fetch -> per page render +
 * capture -> send result -> summary. Runs only while the app is open (v1);
 * failures keep the page queued for the next run (archive, never drop).
 */
class CaptureRunner(
    private val activity: Activity,
    private val transport: QueueTransport,
    private val outDir: File
) {
    data class RunOutcome(val ok: Int, val failed: Int, val failures: List<String>)

    suspend fun runOnce(): RunOutcome = withContext(Dispatchers.Main) {
        outDir.mkdirs()
        val queue = transport.fetchQueue()
        var ok = 0
        val failures = ArrayList<String>()
        val engine = WebViewCaptureEngine(activity)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val device = JSONObject()
            .put("width_dp", activity.resources.configuration.screenWidthDp)
            .put("height_dp", activity.resources.configuration.screenHeightDp)
            .put("density", activity.resources.displayMetrics.density.toDouble())
            .put("font_scale", activity.resources.configuration.fontScale.toDouble())

        for (job in queue) {
            try {
                val settleStart = System.currentTimeMillis()
                val out = engine.capture(job, outDir)
                val settleMs = System.currentTimeMillis() - settleStart - out.loadMs
                val captures = mutableListOf(out.viewportPng.name)
                out.fullPng?.let { captures.add(it.name) }
                val files = mutableListOf(out.viewportPng)
                out.fullPng?.let { files.add(it) }
                iso.timeZone = TimeZone.getDefault()
                val resultJson = QueueProtocol.resultJson(
                    job = job,
                    urlFinal = out.urlFinal,
                    capturedAt = iso.format(Date()),
                    engine = "webview",
                    engineVersion = out.engineVersion,
                    device = device,
                    loadMs = out.loadMs,
                    settleMs = settleMs,
                    captures = captures,
                    status = "ok"
                )
                transport.sendResult(resultJson, files)
                ok++
            } catch (e: Exception) {
                val resultJson = QueueProtocol.resultJson(
                    job = job,
                    urlFinal = job.url,
                    capturedAt = iso.format(Date()),
                    engine = "webview",
                    engineVersion = "",
                    device = device,
                    loadMs = 0,
                    settleMs = 0,
                    captures = emptyList(),
                    status = "failed",
                    error = e.message ?: e.javaClass.simpleName
                )
                transport.sendResult(resultJson, emptyList())
                failures.add("${job.id}: ${e.message}")
            }
        }
        transport.sendSummary(
            "capture run: $ok ok, ${failures.size} failed" +
                if (failures.isNotEmpty()) "\n" + failures.joinToString("\n") else ""
        )
        RunOutcome(ok, failures.size, failures)
    }
}
