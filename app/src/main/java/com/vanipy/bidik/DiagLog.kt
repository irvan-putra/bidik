package com.vanipy.bidik

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * On-device diagnostics log.
 *
 * Purpose: when a run misbehaves on the phone, the log must say which
 * step failed, when, and how long it took - without storing page or
 * account content. Call sites pass stage names, timings, counters and
 * bounded error categories; arbitrary detail text is omitted.
 *
 * Storage: filesDir/logs/events.log (JSON lines). When it grows past the
 * cap it rotates to events-prev.log, so one older generation is kept
 * rather than deleted outright. Both files leave the phone only when the
 * user exports them from the diagnostics view.
 */
object DiagLog {
    private const val MAX_BYTES = 512L * 1024L

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    private fun dir(context: Context) = File(context.filesDir, "logs")
    private fun current(context: Context) = File(dir(context), "events.log")
    private fun previous(context: Context) = File(dir(context), "events-prev.log")

    /** Keep only small, known-safe detail shapes. Never pass free-form text. */
    internal fun sanitize(detail: String): String {
        val category = "(?:dns_resolution_failed|private_address_blocked|cgnat_address_blocked|ula_address_blocked|page_load_failed_or_timed_out|invalid_url_host|network_timeout|io_error|operation_failed)"
        val allowed = listOf(
            Regex("category=$category"),
            Regex("page_index=\\d+(?: of \\d+)?(?: category=$category)?"),
            Regex("pages=\\d+"),
            Regex("stage=(?:queue|capture)"),
            Regex("ok=\\d+ failed=\\d+"),
            Regex("code=-?\\d+"),
            Regex("delay=\\d+ autostop=\\d+ bars=(?:shown|hidden)"),
            Regex("frames=\\d+"),
        )
        return if (allowed.any { it.matches(detail) } || detail == "already in progress") {
            detail
        } else {
            "[details omitted]"
        }
    }

    /** Map arbitrary exception text to a closed set of non-content categories. */
    internal fun errorCategory(error: Throwable): String {
        val message = error.message.orEmpty().lowercase(Locale.US)
        return when {
            message.startsWith("host does not resolve:") -> "dns_resolution_failed"
            message.startsWith("host resolves to a private address:") -> "private_address_blocked"
            message.startsWith("host resolves to a cgnat address:") -> "cgnat_address_blocked"
            message.startsWith("host resolves to a ula address:") -> "ula_address_blocked"
            message.startsWith("page load failed or timed out:") -> "page_load_failed_or_timed_out"
            message.startsWith("no host:") -> "invalid_url_host"
            error is java.net.UnknownHostException -> "dns_resolution_failed"
            error is java.net.SocketTimeoutException -> "network_timeout"
            error is java.io.IOException -> "io_error"
            else -> "operation_failed"
        }
    }

    @Synchronized
    fun log(
        context: Context,
        stage: String,
        event: String,
        ms: Long? = null,
        detail: String? = null
    ) {
        try {
            val line = JSONObject()
                .put("ts", iso.apply { timeZone = TimeZone.getDefault() }.format(Date()))
                .put("stage", stage)
                .put("event", event)
                .apply {
                    ms?.let { put("ms", it) }
                    detail?.let { put("detail", sanitize(it).take(500)) }
                }
                .toString()
            appendLine(current(context), line)
        } catch (_: Exception) {
            // Diagnostics must never break the app.
        }
    }

    internal fun appendLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        if (file.length() > MAX_BYTES) {
            val prev = File(file.parentFile, "events-prev.log")
            prev.delete()
            file.renameTo(prev)
        }
        file.appendText(line + "\n")
    }

    /** Newest-first tail for the on-device viewer. */
    fun tail(context: Context, maxLines: Int = 200): String {
        val cur = current(context)
        if (!cur.exists()) return "(no diagnostics yet)"
        val lines = cur.readLines()
        return lines.takeLast(maxLines).joinToString("\n")
    }

    /** Current log plus the retained previous generation, for export. */
    fun fullText(context: Context): String {
        val prev = previous(context)
        val cur = current(context)
        val sb = StringBuilder()
        if (prev.exists()) sb.append(prev.readText())
        if (cur.exists()) sb.append(cur.readText())
        return sb.toString().ifEmpty { "(no diagnostics yet)" }
    }
}
