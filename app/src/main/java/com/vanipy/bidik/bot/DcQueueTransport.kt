package com.vanipy.bidik.bot

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.vanipy.bidik.QueueProtocol
import com.vanipy.bidik.QueueTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * DeltaChat-backed transport. Talks to the ONE trusted contact the user
 * paired in the setup screen. Used only after pairing; account creation
 * is an explicit setup action, never first-launch automatic
 * (see dc_transport.py). All calls run on IO threads - the embedded core
 * does network I/O.
 */
class DcQueueTransport(
    private val context: Context,
    private val peerAddr: String
) : QueueTransport {

    private val relay = "nine.testrun.org"

    private fun ensurePython() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
    }

    private fun module() = Python.getInstance().getModule("dc_transport")

    private fun nativeLibDir() = context.applicationInfo.nativeLibraryDir
    private fun filesDir() = context.filesDir.absolutePath

    /** Last fetched queue id, kept so it can be marked served after a run. */
    @Volatile
    var lastQueueId: String? = null
        private set

    override suspend fun fetchQueue(): List<QueueProtocol.PageJob> =
        withContext(Dispatchers.IO) {
            ensurePython()
            val raw = module()
                .callAttr("fetch_queue", nativeLibDir(), filesDir(), peerAddr)
                .toString()
            val res = JSONObject(raw)
            if (res.optString("status") != "ok") {
                lastQueueId = null
                return@withContext emptyList()
            }
            lastQueueId = res.getString("queue_id")
            val pages = QueueProtocol.parseQueue(res.getJSONObject("queue").toString())
            // After a partial failure the next sync retries exactly the
            // failed pages; the queue as a whole was never marked served.
            val retry = res.optJSONArray("retry_pages")
            if (retry != null && retry.length() > 0) {
                val ids = (0 until retry.length()).map { retry.getString(it) }.toSet()
                return@withContext pages.filter { it.id in ids }
            }
            pages
        }

    override fun currentQueueId(): String? = lastQueueId

    override suspend fun sendResult(resultJson: String, attachments: List<File>) =
        withContext(Dispatchers.IO) {
            ensurePython()
            val paths = org.json.JSONArray()
            attachments.forEach { paths.put(it.absolutePath) }
            module().callAttr(
                "send_result", nativeLibDir(), filesDir(), peerAddr,
                resultJson, paths.toString()
            )
            Unit
        }

    override suspend fun sendSummary(text: String) = withContext(Dispatchers.IO) {
        ensurePython()
        module().callAttr("send_summary", nativeLibDir(), filesDir(), peerAddr, text)
        Unit
    }

    /**
     * Record the run outcome: full success marks the queue served; partial
     * failure records only the failed pages for retry. Called by the
     * runner host after sendSummary, never on transport error alone.
     */
    suspend fun finishRun(failedPageIds: List<String>) = withContext(Dispatchers.IO) {
        val qid = lastQueueId ?: return@withContext
        ensurePython()
        val ids = org.json.JSONArray()
        failedPageIds.forEach { ids.put(it) }
        module().callAttr("record_queue_outcome", nativeLibDir(), filesDir(), qid, ids.toString())
        lastQueueId = null
        Unit
    }
}
