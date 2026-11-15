package com.vanipy.mata

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pairing state and the explicit setup actions. Nothing here runs by
 * itself: provisioning happens only from the setup screen button.
 */
object SetupController {
    private const val PREFS = "mata"
    private const val KEY_PEER = "peer_addr"
    private const val KEY_PAIRED = "paired"
    private const val KEY_PROVISIONED = "provisioned"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun peerAddr(context: Context): String? = prefs(context).getString(KEY_PEER, null)

    fun isPaired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAIRED, false) && peerAddr(context) != null

    /** Account creation gates pairing: the handshake needs the account's
     *  own transport. The flag is set only after a provision/selfAddress
     *  call actually confirms the account. */
    fun isProvisioned(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROVISIONED, false)

    /**
     * Pair with the assistant by completing its secure-join invite QR.
     * Chatmail requires end-to-end encryption, so a typed address can
     * never be paired with; the QR handshake is the only path. Blocks
     * until the handshake completes. Returns the paired peer address.
     */
    suspend fun completePairing(context: Context, qrText: String): String =
        withContext(Dispatchers.IO) {
            // Never log the QR payload: it carries the inviter's key material.
            val start = System.currentTimeMillis()
            DiagLog.log(context, "pair", "start")
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context))
                val result = Python.getInstance().getModule("dc_transport")
                    .callAttr(
                        "complete_pairing",
                        context.applicationInfo.nativeLibraryDir,
                        context.filesDir.absolutePath,
                        qrText.trim()
                    )
                    .toString()
                recordPairedPeer(context, result).also {
                    DiagLog.log(context, "pair", "ok", ms = System.currentTimeMillis() - start)
                }
            } catch (e: Exception) {
                DiagLog.log(context, "pair", "failed", ms = System.currentTimeMillis() - start, detail = "category=${DiagLog.errorCategory(e)}")
                throw e
            }
        }

    /** Parse the module's pairing result and store the peer. Split out
     *  so the exact contract with dc_transport.complete_pairing is unit
     *  testable without a Python runtime. */
    fun recordPairedPeer(context: Context, resultJson: String): String {
        val addr = org.json.JSONObject(resultJson).getString("peer_addr")
        prefs(context).edit()
            .putString(KEY_PEER, addr)
            .putBoolean(KEY_PAIRED, true)
            .apply()
        return addr
    }

    fun unpair(context: Context) {
        prefs(context).edit().putBoolean(KEY_PAIRED, false).apply()
        DiagLog.log(context, "pair", "unpaired")
    }

    /** Explicit-setup account creation. Returns the app's address. */
    suspend fun provision(context: Context, relay: String): String =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            DiagLog.log(context, "account", "create_start")
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context))
                Python.getInstance().getModule("dc_transport")
                    .callAttr(
                        "provision",
                        context.applicationInfo.nativeLibraryDir,
                        context.filesDir.absolutePath,
                        relay
                    )
                    .toString()
                    .also {
                        prefs(context).edit().putBoolean(KEY_PROVISIONED, true).apply()
                        DiagLog.log(context, "account", "create_ok", ms = System.currentTimeMillis() - start)
                    }
            } catch (e: Exception) {
                DiagLog.log(context, "account", "create_failed", ms = System.currentTimeMillis() - start, detail = "category=${DiagLog.errorCategory(e)}")
                throw e
            }
        }

    suspend fun selfAddress(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            Python.getInstance().getModule("dc_transport")
                .callAttr(
                    "self_address",
                    context.applicationInfo.nativeLibraryDir,
                    context.filesDir.absolutePath
                )
                .toString()
                .also { prefs(context).edit().putBoolean(KEY_PROVISIONED, true).apply() }
        } catch (e: Exception) {
            null
        }
    }
}
