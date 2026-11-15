package com.vanipy.mata

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Regression coverage for overlapping runs: the Run button, the on-open
 * auto run and the post-pairing callback can fire together, and a second
 * run would share DcQueueTransport.lastQueueId (wrong ledger) and could
 * collide on the run directory.
 */
@RunWith(RobolectricTestRunner::class)
class RunGateTest {

    @Test
    fun secondRunRejectedUntilFirstReleases() {
        val gate = RunGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun runDirsAreUniquePerCall() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("mata", 0).edit().clear().commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val first = activity.newRunDir(File("/tmp/x"))
        val second = activity.newRunDir(File("/tmp/x"))
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertTrue(first.absolutePath.contains("captures/run-"))
        assertEquals(first.parent, second.parent)
    }
}
