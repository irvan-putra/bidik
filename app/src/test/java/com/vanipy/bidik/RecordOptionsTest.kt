package com.vanipy.bidik

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordOptionsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun defaultsMatchTheAnnouncedBehavior() {
        context.getSharedPreferences("record_options", 0).edit().clear().commit()
        // A short delay is the default: the feature exists so the app UI
        // can be left before the clip starts.
        assertEquals(3, RecordOptions.delaySeconds(context))
        // Recordings only end on purpose unless a duration is chosen.
        assertEquals(0, RecordOptions.autoStopSeconds(context))
        // Full-screen output stays the default.
        assertFalse(RecordOptions.barsHidden(context))
    }

    @Test
    fun cyclingWrapsThroughEveryChoice() {
        context.getSharedPreferences("record_options", 0).edit().clear().commit()
        val seen = mutableListOf<Int>()
        var current = RecordOptions.delaySeconds(context)
        repeat(RecordOptions.DELAY_CHOICES.size) {
            seen.add(current)
            current = RecordOptions.cycleDelay(context)
        }
        assertEquals(RecordOptions.DELAY_CHOICES.toList(), seen.sorted())
        assertEquals(RecordOptions.DELAY_CHOICES.first().let { seen.first() }, current)
    }

    @Test
    fun persistsChoices() {
        RecordOptions.setDelaySeconds(context, 10)
        RecordOptions.setAutoStopSeconds(context, 30)
        RecordOptions.setBarsHidden(context, true)
        assertEquals(10, RecordOptions.delaySeconds(context))
        assertEquals(30, RecordOptions.autoStopSeconds(context))
        assertTrue(RecordOptions.barsHidden(context))
        assertEquals("10s", RecordOptions.delayLabel(10))
        assertEquals("off", RecordOptions.autoStopLabel(0))
        assertEquals("30s", RecordOptions.autoStopLabel(30))
        assertEquals("2m", RecordOptions.autoStopLabel(120))
        RecordOptions.setBarsHidden(context, false)
        RecordOptions.setDelaySeconds(context, 3)
        RecordOptions.setAutoStopSeconds(context, 0)
    }
}
