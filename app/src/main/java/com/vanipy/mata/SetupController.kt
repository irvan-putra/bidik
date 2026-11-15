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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun peerAddr(context: Context): String? = prefs(context).getString(KEY_PEER, null)

    fun isPaired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAIRED, false) && peerAddr(context) != null

    fun pair(context: Context, addr: String) {
        prefs(context).edit()
            .putString(KEY_PEER, addr.trim())
            .putBoolean(KEY_PAIRED, true)
            .apply()
    }

    fun unpair(context: Context) {
        prefs(context).edit().putBoolean(KEY_PAIRED, false).apply()
    }

    /** Explicit-setup account creation. Returns the app's address. */
    suspend fun provision(context: Context, relay: String): String =
        withContext(Dispatchers.IO) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            Python.getInstance().getModule("dc_transport")
                .callAttr(
                    "provision",
                    context.applicationInfo.nativeLibraryDir,
                    context.filesDir.absolutePath,
                    relay
                )
                .toString()
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
        } catch (e: Exception) {
            null
        }
    }
}
