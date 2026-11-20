package com.vanipy.bidik

import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own open queue/result message protocol (v1, draft).
 *
 * This is the app's public interface spec: any trusted peer that can reach
 * the configured transport contact may send a queue and receive results.
 * It intentionally says nothing about who the peer is or how it works.
 *
 * Queue message: JSON body (or capture-queue.json attachment):
 *   {"type":"capture-queue","v":1,"pages":[{"id":"...","url":"...",
 *     "mode":"webview","full_page":true,"wait_ms":3000,"notes":"..."}]}
 *
 * Result message: JSON body + PNG attachment(s):
 *   {"type":"capture-result","v":1,"queue_id":"...","page_id":"...","url_final":"...",
 *    "captured_at":"...","engine":"webview","webview_version":"...",
 *    "device":{"width_dp":..,"height_dp":..,"density":..,"font_scale":..},
 *    "load_ms":..,"settle_ms":..,"captures":["..."],"status":"ok|failed",
 *    "error":"..."}
 */
object QueueProtocol {
    const val QUEUE_TYPE = "capture-queue"
    const val RESULT_TYPE = "capture-result"
    const val VERSION = 1

    data class PageJob(
        val id: String,
        val url: String,
        val mode: String = "webview",
        val fullPage: Boolean = true,
        val waitMs: Long = 3000,
        val notes: String = ""
    )

    /** Hard cap so a malformed or hostile queue cannot burn unbounded time/data. */
    const val MAX_PAGES = 20

    fun parseQueue(json: String): List<PageJob> {
        val root = JSONObject(json)
        require(root.optString("type") == QUEUE_TYPE) { "not a capture-queue message" }
        val pages = root.getJSONArray("pages")
        require(pages.length() <= MAX_PAGES) { "queue has ${pages.length()} pages, max $MAX_PAGES" }
        val out = ArrayList<PageJob>(pages.length())
        for (i in 0 until pages.length()) {
            val p = pages.getJSONObject(i)
            val url = p.getString("url")
            require(UrlGuard.isAllowed(url)) { "url not allowed: $url" }
            out.add(
                PageJob(
                    id = p.getString("id").take(64),
                    url = url,
                    mode = p.optString("mode", "webview"),
                    fullPage = p.optBoolean("full_page", true),
                    waitMs = p.optLong("wait_ms", 3000).coerceIn(0, 15000),
                    notes = p.optString("notes", "").take(500)
                )
            )
        }
        return out
    }

    fun resultJson(
        job: PageJob,
        queueId: String,
        urlFinal: String,
        capturedAt: String,
        engine: String,
        engineVersion: String,
        device: JSONObject,
        loadMs: Long,
        settleMs: Long,
        captures: List<String>,
        status: String,
        error: String? = null
    ): String {
        val r = JSONObject()
        r.put("type", RESULT_TYPE)
        r.put("v", VERSION)
        r.put("queue_id", queueId)
        r.put("page_id", job.id)
        r.put("url_final", urlFinal)
        r.put("captured_at", capturedAt)
        r.put("engine", engine)
        r.put("webview_version", engineVersion)
        r.put("device", device)
        r.put("load_ms", loadMs)
        r.put("settle_ms", settleMs)
        r.put("captures", JSONArray(captures))
        r.put("status", status)
        if (error != null) r.put("error", error)
        return r.toString()
    }
}
