package com.vanipy.mata

import java.io.File

/**
 * Transport interface between the app and the queue peer.
 *
 * The app is peer-agnostic: it talks to ONE trusted contact the user
 * configures at runtime (address entered in-app, never in source). This
 * interface deliberately hides which transport carries the messages; the
 * DeltaChat-backed implementation lives under bot/ and is gated behind
 * explicit setup (see TRANSPORT_ENABLED in BuildConfig wiring).
 */
interface QueueTransport {
    /** Latest queue from the peer; empty list when none is pending. */
    suspend fun fetchQueue(): List<QueueProtocol.PageJob>

    /** Send one page's result JSON + its PNG files back to the peer. */
    suspend fun sendResult(resultJson: String, attachments: List<File>)

    /** Send a run summary (per-page ok/failed). */
    suspend fun sendSummary(text: String)
}

/**
 * Local transport for development and CI pixel checks: reads a queue JSON
 * from the app's files dir, writes results to files dir. No network, no
 * account. The capture-check instrumented test uses this so the full
 * pipeline is exercised without any chatmail account.
 */
class LocalQueueTransport(private val filesDir: File) : QueueTransport {
    override suspend fun fetchQueue(): List<QueueProtocol.PageJob> {
        val f = File(filesDir, "capture-queue.json")
        if (!f.exists()) return emptyList()
        return QueueProtocol.parseQueue(f.readText())
    }

    override suspend fun sendResult(resultJson: String, attachments: List<File>) {
        File(filesDir, "last-result.json").writeText(resultJson)
    }

    override suspend fun sendSummary(text: String) {
        File(filesDir, "last-summary.txt").writeText(text)
    }
}
