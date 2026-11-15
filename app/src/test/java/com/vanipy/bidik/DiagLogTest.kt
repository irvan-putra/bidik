package com.vanipy.bidik

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagLogTest {

    private fun app() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun writesJsonLinesWithStageTimingAndDetail() {
        val c = app()
        val log = File(c.filesDir, "logs/events.log")
        log.parentFile?.deleteRecursively()
        DiagLog.log(c, "capture", "failed", ms = 123, detail = "page_index=1 category=network_timeout")
        val line = JSONObject(log.readLines().last())
        assertEquals("capture", line.getString("stage"))
        assertEquals("failed", line.getString("event"))
        assertEquals(123, line.getInt("ms"))
        assertEquals("page_index=1 category=network_timeout", line.getString("detail"))
        assertTrue(line.getString("ts").isNotBlank())
    }

    @Test
    fun categoriesDoNotRetainUrlsPageIdsOrExceptionText() {
        val c = app()
        val log = File(c.filesDir, "logs/events.log")
        log.parentFile?.deleteRecursively()
        val url = "https://example.com/private/path?token=secret-value"
        val pageId = "private-page-identifier"
        val error = WebViewCaptureEngine.CaptureException("page load failed or timed out: $url $pageId")
        val detail = "page_index=1 category=${DiagLog.errorCategory(error)}"
        DiagLog.log(c, "capture", "failed", ms = 321, detail = detail)
        val text = DiagLog.fullText(c)
        assertTrue(text.contains("page_load_failed_or_timed_out"))
        assertFalse(text.contains("example.com"))
        assertFalse(text.contains("private/path"))
        assertFalse(text.contains("secret-value"))
        assertFalse(text.contains(pageId))
    }

    @Test
    fun dnsCategoryOmitsHost() {
        val c = app()
        val log = File(c.filesDir, "logs/events.log")
        log.parentFile?.deleteRecursively()
        val error = WebViewCaptureEngine.CaptureException("host does not resolve: internal.example.test")
        DiagLog.log(c, "capture", "failed", detail = "category=${DiagLog.errorCategory(error)}")
        val text = DiagLog.fullText(c)
        assertTrue(text.contains("dns_resolution_failed"))
        assertFalse(text.contains("internal.example.test"))
    }


    @Test
    fun sanitizerKeepsOnlyBoundedFieldsAndOmitsFreeFormIdentifiers() {
        assertEquals("page_index=2 category=dns_resolution_failed", DiagLog.sanitize("page_index=2 category=dns_resolution_failed"))
        assertEquals("ok=2 failed=1", DiagLog.sanitize("ok=2 failed=1"))
        val secretLike = "category=dns_resolution_failed host=internal.example.test https://example.test/private?id=page-123 exception=bad"
        assertEquals("[details omitted]", DiagLog.sanitize(secretLike))
    }

    @Test
    fun rotationKeepsOnePreviousGeneration() {
        val c = app()
        val dir = File(c.filesDir, "logs")
        dir.deleteRecursively()
        val cur = File(dir, "events.log")
        val prev = File(dir, "events-prev.log")
        // Fill the current log past the 512KB cap directly (filling via
        // DiagLog.log would rotate mid-loop and never terminate).
        cur.parentFile?.mkdirs()
        cur.writeText("x".repeat(600 * 1024) + "\n")
        val before = cur.length()
        DiagLog.log(c, "test", "rotate")
        assertTrue("rotation should have moved the full log aside", prev.exists())
        assertTrue("new current log should be small", cur.length() < before)
        assertTrue(cur.readLines().last().contains("rotate"))
        assertTrue(prev.readText().contains("xxx"))
    }

    @Test
    fun tailAndFullTextExposeRecentHistory() {
        val c = app()
        val dir = File(c.filesDir, "logs")
        dir.deleteRecursively()
        DiagLog.log(c, "app", "open")
        DiagLog.log(c, "run", "end", ms = 42)
        val tail = DiagLog.tail(c)
        assertTrue(tail.contains("\"event\":\"open\""))
        assertTrue(tail.contains("\"event\":\"end\""))
        assertEquals(tail, DiagLog.fullText(c).trim())
    }
}
