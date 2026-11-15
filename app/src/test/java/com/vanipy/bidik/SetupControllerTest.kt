package com.vanipy.bidik

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Kotlin-side contract with dc_transport.complete_pairing: the
 * module returns {"status":"ok","peer_addr":...} and the controller
 * stores exactly that returned address as the paired peer.
 */
@RunWith(RobolectricTestRunner::class)
class SetupControllerTest {

    private val app: android.app.Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun recordPairedPeerStoresReturnedAddress() {
        val addr = SetupController.recordPairedPeer(
            app, """{"status":"ok","peer_addr":"peer@example.org"}"""
        )
        assertEquals("peer@example.org", addr)
        assertEquals("peer@example.org", SetupController.peerAddr(app))
        assertTrue(SetupController.isPaired(app))
    }

    @Test(expected = org.json.JSONException::class)
    fun recordPairedPeerRejectsResultWithoutAddress() {
        SetupController.recordPairedPeer(app, """{"status":"ok"}""")
    }

    @Test
    fun unpairedByDefault() {
        val fresh = app.getSharedPreferences("bidik", 0)
        fresh.edit().clear().commit()
        assertFalse(SetupController.isPaired(app))
    }
}
