package com.vanipy.bidik

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the lost-ledger bug: runCapture used one
 * freshly built transport for the run and ANOTHER for finishRun, so
 * DcQueueTransport.lastQueueId never survived and the served/retry
 * ledger was never recorded. currentTransport() must return one stable
 * instance per pairing state.
 */
@RunWith(RobolectricTestRunner::class)
class TransportIdentityTest {

    @Test
    fun currentTransportReturnsTheSameInstanceAcrossCalls() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("bidik", 0).edit().clear().commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val first = activity.currentTransport()
        val second = activity.currentTransport()
        assertSame(first, second)
        assertTrue(first is LocalQueueTransport)
    }
}
