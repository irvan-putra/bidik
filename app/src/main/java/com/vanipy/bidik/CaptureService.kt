package com.vanipy.bidik

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
    data class RunOutcome(
        val ok: Int,
        val failed: Int,
        val failures: List<String>,
        val failedIds: List<String>
    )

    suspend fun runOnce(): RunOutcome = withContext(Dispatchers.Main) {
        outDir.mkdirs()
        val runLog = File(outDir, "run.log")
        val runStart = System.currentTimeMillis()
        DiagLog.log(activity, "run", "start")
        val queueStart = System.currentTimeMillis()
        val queue = try {
            transport.fetchQueue().also {
                DiagLog.log(activity, "queue", "ok", ms = System.currentTimeMillis() - queueStart, detail = "pages=${it.size}")
            }
        } catch (e: Exception) {
            DiagLog.log(activity, "queue", "failed", ms = System.currentTimeMillis() - queueStart, detail = "category=${DiagLog.errorCategory(e)}")
            DiagLog.log(activity, "run", "failed", ms = System.currentTimeMillis() - runStart, detail = "stage=queue")
            throw e
        }
        var ok = 0
        val failures = ArrayList<String>()
        val failedIds = ArrayList<String>()
        val engine = WebViewCaptureEngine(activity)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val device = JSONObject()
            .put("width_dp", activity.resources.configuration.screenWidthDp)
            .put("height_dp", activity.resources.configuration.screenHeightDp)
            .put("density", activity.resources.displayMetrics.density.toDouble())
            .put("font_scale", activity.resources.configuration.fontScale.toDouble())

        for ((index, job) in queue.withIndex()) {
            val pageStart = System.currentTimeMillis()
            try {
                val settleStart = pageStart
                val out = engine.capture(job, outDir)
                val settleMs = System.currentTimeMillis() - settleStart - out.loadMs
                val captures = mutableListOf(out.viewportPng.name)
                out.fullPng?.let { captures.add(it.name) }
                val files = mutableListOf(out.viewportPng)
                out.fullPng?.let { files.add(it) }
                iso.timeZone = TimeZone.getDefault()
                val resultJson = QueueProtocol.resultJson(
                    job = job,
                    queueId = transport.currentQueueId() ?: "unknown",
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
                val pageMs = System.currentTimeMillis() - settleStart
                DiagLog.log(activity, "capture", "ok", ms = pageMs, detail = "page_index=${index + 1}")
                DiagLog.appendLine(runLog, "ok page_index=${index + 1} ${pageMs}ms")
            } catch (e: Exception) {
                val resultJson = QueueProtocol.resultJson(
                    job = job,
                    queueId = transport.currentQueueId() ?: "unknown",
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
                failedIds.add(job.id)
                val pageMs = System.currentTimeMillis() - pageStart
                val category = DiagLog.errorCategory(e)
                DiagLog.log(activity, "capture", "failed", ms = pageMs, detail = "page_index=${index + 1} category=$category")
                DiagLog.appendLine(runLog, "failed page_index=${index + 1} ${pageMs}ms category=$category")
            }
        }
        transport.sendSummary(
            "capture run: $ok ok, ${failures.size} failed" +
                if (failures.isNotEmpty()) "\n" + failures.joinToString("\n") else ""
        )
        DiagLog.log(
            activity, "run", "end",
            ms = System.currentTimeMillis() - runStart,
            detail = "ok=$ok failed=${failures.size}"
        )
        RunOutcome(ok, failures.size, failures, failedIds)
    }
}
