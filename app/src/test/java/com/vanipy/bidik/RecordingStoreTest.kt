package com.vanipy.bidik

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class RecordingStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `new recording file uses the bidik mp4 name inside recordings dir`() {
        val now = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse("20261119-010203")!!
        val file = RecordingStore.newRecordingFile(context, now)
        assertEquals("recordings", file.parentFile?.name)
        assertEquals("bidik-20261119-010203.mp4", file.name)
    }

    @Test
    fun `delete removes a saved recording`() {
        val dir = RecordingStore.dir(context)
        dir.mkdirs()
        val file = File(dir, "bidik-20261119-092000.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(RecordingStore.delete(context, file.name))
        assertTrue(!file.exists())
    }

    @Test
    fun `delete refuses paths outside the recordings dir`() {
        assertTrue(!RecordingStore.delete(context, "../escape.mp4"))
        assertTrue(!RecordingStore.delete(context, "/etc/hostname"))
        assertTrue(!RecordingStore.delete(context, "bidik-never-existed.mp4"))
    }

    @Test
    fun `delete refuses the file an active session is writing`() {
        val dir = RecordingStore.dir(context)
        dir.mkdirs()
        val file = File(dir, "bidik-20261119-092100.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3))
        RecordingStore.setActive(context, file.name)
        try {
            assertTrue(!RecordingStore.delete(context, file.name))
            assertTrue(file.exists())
        } finally {
            RecordingStore.clearActive(context)
            file.delete()
        }
    }

    @Test
    fun `new recording file never reuses an existing name`() {
        val now = Date(0L)
        val first = RecordingStore.newRecordingFile(context, now)
        first.parentFile?.mkdirs()
        first.writeBytes(byteArrayOf(1))
        val second = RecordingStore.newRecordingFile(context, now)
        assertNotEquals(first.name, second.name)
        assertTrue(second.name.startsWith(first.name.removeSuffix(".mp4")))
    }

    @Test
    fun `list returns mp4 files newest first and ignores other files`() {
        val dir = RecordingStore.dir(context)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val older = File(dir, "bidik-a.mp4").apply { writeBytes(byteArrayOf(1)); setLastModified(1000L) }
        val newer = File(dir, "bidik-b.mp4").apply { writeBytes(byteArrayOf(1)); setLastModified(2000L) }
        File(dir, "notes.txt").writeBytes(byteArrayOf(1))
        assertEquals(listOf(newer, older), RecordingStore.list(context))
    }

    @Test
    fun `active marker sets reads and clears`() {
        RecordingStore.clearActive(context)
        assertNull(RecordingStore.activeName(context))
        RecordingStore.setActive(context, "bidik-x.mp4")
        assertEquals("bidik-x.mp4", RecordingStore.activeName(context))
        RecordingStore.clearActive(context)
        assertNull(RecordingStore.activeName(context))
    }

    @Test
    fun `video bit rate scales with pixels and stays inside its cap`() {
        val small = RecordingStore.videoBitRate(720, 1280)
        val full = RecordingStore.videoBitRate(1080, 2400)
        val huge = RecordingStore.videoBitRate(4000, 4000)
        assertTrue(small < full)
        assertTrue(small >= 1_000_000)
        assertEquals(20_000_000, huge)
        assertTrue(full <= 20_000_000)
    }

    @Test
    fun `duration cap is positive and bounded`() {
        assertTrue(RecordingStore.MAX_DURATION_MS > 0)
        assertTrue(RecordingStore.MAX_DURATION_MS <= 30L * 60L * 1000L)
    }

    @Test
    fun `free space check reflects the recordings dir`() {
        // The sandbox filesystem has far more than the minimum, so the
        // check passes; this pins the method's wiring rather than a value.
        assertTrue(RecordingStore.hasFreeSpace(context))
    }
}
