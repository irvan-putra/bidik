package com.vanipy.bidik

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueProtocolTest {

    @Test
    fun parsesQueueWithDefaults() {
        val jobs = QueueProtocol.parseQueue(
            """{"type":"capture-queue","v":1,"pages":[{"id":"a","url":"https://example.com/"}]}"""
        )
        assertEquals(1, jobs.size)
        assertEquals("a", jobs[0].id)
        assertEquals("webview", jobs[0].mode)
        assertEquals(true, jobs[0].fullPage)
        assertEquals(3000, jobs[0].waitMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonQueue() {
        QueueProtocol.parseQueue("""{"type":"other"}""")
    }

    @Test
    fun clampsWait() {
        val jobs = QueueProtocol.parseQueue(
            """{"type":"capture-queue","v":1,"pages":[{"id":"a","url":"https://example.com/","wait_ms":999999}]}"""
        )
        assertEquals(15000, jobs[0].waitMs)
    }

    @Test
    fun resultJsonRoundTrip() {
        val s = QueueProtocol.resultJson(
            job = QueueProtocol.PageJob("a", "https://example.com/"),
            queueId = "q-9",
            urlFinal = "https://example.com/",
            capturedAt = "2026-11-15T07:30:00+07:00",
            engine = "webview",
            engineVersion = "130.0",
            device = JSONObject().put("width_dp", 360),
            loadMs = 100,
            settleMs = 200,
            captures = listOf("a-viewport.png"),
            status = "ok"
        )
        val r = JSONObject(s)
        assertEquals("capture-result", r.getString("type"))
        assertEquals("q-9", r.getString("queue_id"))
        assertEquals("a", r.getString("page_id"))
        assertTrue(r.getJSONArray("captures").length() == 1)
    }
}
