package com.vanipy.bidik.jin

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** A call to Jin failed; [code] is one of JinContract.E_* (or a provider-side reason). */
class JinException(val code: String, message: String? = null) :
    Exception(message ?: code)

/**
 * The raw provider surface, so host tests can fake it without Android
 * binder. The real implementation talks to Jin's exported provider.
 */
interface JinProviderCalls {
    fun call(method: String, arg: String?, extras: Bundle?): Bundle
    fun openWrite(uri: Uri): ParcelFileDescriptor?
}

class AndroidJinProviderCalls(private val resolver: ContentResolver) : JinProviderCalls {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        resolver.call(JinContract.AUTHORITY, method, arg, extras)
            ?: Bundle().apply {
                putBoolean(JinContract.K_OK, false)
                putString(JinContract.K_ERROR, JinContract.E_MALFORMED)
            }

    override fun openWrite(uri: Uri): ParcelFileDescriptor? =
        try {
            resolver.openFileDescriptor(uri, "w")
        } catch (e: Exception) {
            null
        }
}

/**
 * Bidik's client for the Jin cross-app transport (contract v1).
 *
 * Direction covered here: Bidik -> Jin (send text/files into granted
 * chats, check status) and Jin -> Bidik via polling ([poll] drains the
 * inbox the owner granted; there is no push in v1, so commands arrive
 * only when Bidik polls, typically while it is in the foreground).
 *
 * Every send takes a caller-supplied token. Token discipline (contract
 * v1): a fresh UUID per NEW send; retrying an unanswered send ALWAYS
 * reuses that send's token and body. Minting a fresh token to retry
 * the same logical send is a contract violation - a fresh token is a
 * NEW send and duplicates the message when the original commit landed.
 *
 * Recovery: a same-token retry of the same body returns the original
 * ref without re-sending (text: 7-day ledger; file: retained bytes for
 * ~10 minutes, then EXPIRED on a bare commit). sendFile stages before
 * every commit, so a file retry after EXPIRED simply calls sendFile
 * again with the same token, bytes and metadata - the provider
 * re-verifies identity against the ledger and returns the original
 * ref. TOKEN_REUSED always means a body/token mismatch: fail closed,
 * never swap bodies under one token.
 */
class JinClient(private val provider: JinProviderCalls) {

    data class Capabilities(
        val version: Int,
        val maxBytes: Long,
        val granted: Boolean,
        val prefix: String?
    )

    /** [send]/[recv] mirror the owner's two independent per-chat grant scopes. */
    data class Chat(val handle: String, val name: String, val send: Boolean, val recv: Boolean)

    data class SendResult(val ref: String, val status: String)

    data class InboxItem(
        val seq: Long,
        val ts: Long,
        val chat: String,
        val from: String,
        val text: String
    )

    /**
     * [gap] true means the cursor pointed at entries Jin's bounded
     * retention already dropped, so commands may have been missed;
     * [firstAvailable] is the oldest retained seq. Surface this, never
     * silently skip.
     */
    data class PollResult(
        val items: List<InboxItem>,
        val cursor: String,
        val gap: Boolean,
        val firstAvailable: String?
    )

    data class Status(val status: String, val reason: String)

    fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    /** The only call that works without a grant; also how Jin first sees Bidik. */
    fun capabilities(): Capabilities {
        val b = checked(provider.call(JinContract.M_CAPABILITIES, null, null))
        return Capabilities(
            version = b.getInt(JinContract.K_CONTRACT_VERSION),
            maxBytes = b.getLong(JinContract.K_MAX_BYTES, JinContract.DEFAULT_MAX_BYTES),
            granted = b.getBoolean(JinContract.K_GRANTED, false),
            prefix = if (b.containsKey(JinContract.K_PREFIX)) b.getString(JinContract.K_PREFIX) else null
        )
    }

    /** Chats the owner granted Bidik, with display names. Requires a grant. */
    fun listChats(): List<Chat> {
        val b = checked(provider.call(JinContract.M_LIST_CHATS, null, null))
        val arr = JSONArray(b.getString(JinContract.K_ITEMS, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Chat(
                o.getString("handle"),
                o.optString("name", o.getString("handle")),
                o.optBoolean("send", false),
                o.optBoolean("recv", false)
            )
        }
    }

    fun sendText(token: String, chat: String, text: String): SendResult {
        requireToken(token)
        val meta = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("chat", chat)
            .put("text", text)
        return commit(token, meta)
    }

    /**
     * Stages [file] through the provider pipe, then commits. Files larger
     * than [maxBytes] (advertised by [capabilities]) are refused before
     * any staging. On any commit failure the stage is cancelled
     * best-effort so nothing lingers in Jin's staging area.
     */
    fun sendFile(
        token: String,
        chat: String,
        text: String,
        file: File,
        filename: String,
        mime: String,
        maxBytes: Long = JinContract.DEFAULT_MAX_BYTES
    ): SendResult {
        requireToken(token)
        if (file.length() > maxBytes) throw JinException(JinContract.E_FILE_TOO_LARGE)
        val stage = checked(provider.call(JinContract.M_STAGE, token, null))
        val uri = stage.getString(JinContract.K_URI)
            ?: throw JinException(JinContract.E_MALFORMED, "stage returned no uri")
        try {
            streamTo(uri, file)
            val meta = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("chat", chat)
                .put("text", text)
                .put("filename", filename)
                .put("mime", mime)
            try {
                return commit(token, meta)
            } catch (e: JinException) {
                // The provider's retry path does not await the drain's EOF
                // marker, so an immediate same-token re-stage + commit can
                // report EXPIRED while the bytes are still landing. Bounded
                // backoff retries absorb that race even on a busy device;
                // a persistent EXPIRED is real and surfaces.
                if (e.code != JinContract.E_EXPIRED) throw e
                var last = e
                for (waitMs in longArrayOf(300L, 600L, 1_200L)) {
                    Thread.sleep(waitMs)
                    try {
                        return commit(token, meta)
                    } catch (again: JinException) {
                        if (again.code != JinContract.E_EXPIRED) throw again
                        last = again
                    }
                }
                throw last
            }
        } catch (e: Exception) {
            try {
                provider.call(JinContract.M_CANCEL, token, null)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    fun cancel(token: String) {
        requireToken(token)
        checked(provider.call(JinContract.M_CANCEL, token, null))
    }

    fun status(ref: String): Status {
        val b = checked(provider.call(JinContract.M_STATUS, ref, null))
        return Status(b.getString(JinContract.K_STATUS, "") ?: "", b.getString("reason", "") ?: "")
    }

    /**
     * Drains the granted inbox after [cursor] ("" or "0" for everything
     * Jin still holds). Returns items and the cursor to persist for the
     * next poll. Requires a grant and a running bot.
     */
    fun poll(cursor: String): PollResult {
        val b = checked(provider.call(JinContract.M_POLL, cursor, null))
        val arr = JSONArray(b.getString(JinContract.K_ITEMS, "[]"))
        val items = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            InboxItem(
                seq = o.optLong("seq"),
                ts = o.optLong("ts"),
                chat = o.optString("chat", ""),
                from = o.optString("from", ""),
                text = o.optString("text", "")
            )
        }
        return PollResult(
            items,
            b.getString(JinContract.K_CURSOR, cursor) ?: cursor,
            b.getBoolean(JinContract.K_GAP, false),
            if (b.containsKey(JinContract.K_FIRST_AVAILABLE)) b.getString(JinContract.K_FIRST_AVAILABLE) else null
        )
    }

    private fun commit(token: String, meta: JSONObject): SendResult {
        val extras = Bundle().apply { putString(JinContract.K_META, meta.toString()) }
        val b = checked(provider.call(JinContract.M_COMMIT, token, extras))
        return SendResult(
            ref = b.getString(JinContract.K_REF)
                ?: throw JinException(JinContract.E_MALFORMED, "commit returned no ref"),
            status = b.getString(JinContract.K_STATUS, JinContract.S_QUEUED)
        )
    }

    private fun streamTo(uri: String, file: File) {
        val pfd = provider.openWrite(Uri.parse(uri))
            ?: throw JinException(JinContract.E_MALFORMED, "stage pipe refused")
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            try {
                pfd.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun checked(b: Bundle): Bundle {
        if (!b.getBoolean(JinContract.K_OK, false)) {
            throw JinException(b.getString(JinContract.K_ERROR) ?: JinContract.E_MALFORMED)
        }
        return b
    }

    private fun requireToken(token: String) {
        if (!JinContract.TOKEN_REGEX.matches(token)) {
            throw JinException(JinContract.E_BAD_TOKEN, "token must match ${JinContract.TOKEN_REGEX}")
        }
    }
}
