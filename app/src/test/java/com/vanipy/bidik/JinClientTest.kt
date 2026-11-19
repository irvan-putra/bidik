package com.vanipy.bidik

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.vanipy.bidik.jin.JinClient
import com.vanipy.bidik.jin.JinContract
import com.vanipy.bidik.jin.JinException
import com.vanipy.bidik.jin.JinProviderCalls
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Host-side contract tests for the Jin transport client. A fake provider
 * plays Jin's side (auth, staging pipe, dedup ledger) closely enough to
 * pin the client's behavior: exact method names, meta shape, token rules,
 * error propagation, and retry idempotency. The real joint proof is the
 * cross-app emulator/device test, which is still pending on the Jin side.
 */
@RunWith(RobolectricTestRunner::class)
class JinClientTest {

    /** Minimal fake of Jin's provider: grant-gated, pipe staging, ledger. */
    private class FakeJin(
        var granted: Boolean = true,
        var botRunning: Boolean = true
    ) : JinProviderCalls {
        val stagedBytes = HashMap<String, ByteArray>()
        val cancelled = HashSet<String>()
        val ledger = HashMap<String, Pair<String, String>>() // token -> (hash, ref)
        var lastMeta: JSONObject? = null
        var lastMethod: String? = null
        var refCounter = 0
        var inboxJson = "[]"
        var inboxCursor = "0"
        var listChatsJson = "[]"

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            lastMethod = method
            if (method == JinContract.M_CAPABILITIES) {
                return ok().apply {
                    putInt(JinContract.K_CONTRACT_VERSION, 1)
                    putLong(JinContract.K_MAX_BYTES, JinContract.DEFAULT_MAX_BYTES)
                    putBoolean(JinContract.K_GRANTED, granted)
                    if (granted) putString(JinContract.K_PREFIX, "/bidik")
                }
            }
            if (!granted) return err(JinContract.E_NOT_AUTHORIZED)
            return when (method) {
                JinContract.M_STAGE -> ok().apply {
                    putString(JinContract.K_URI, JinContract.stageUri(arg!!))
                }
                JinContract.M_CANCEL -> {
                    arg?.let { cancelled.add(it); stagedBytes.remove(it) }
                    ok()
                }
                JinContract.M_COMMIT -> {
                    if (!botRunning) return err(JinContract.E_BOT_NOT_RUNNING)
                    val meta = JSONObject(extras!!.getString(JinContract.K_META)!!)
                    lastMeta = meta
                    val fileBytes = stagedBytes[arg]
                    val canon = "send|" + meta.optString("chat") + "|" + meta.optString("text") +
                        "|" + meta.optString("filename") + "|" + meta.optString("mime") + "|" +
                        (fileBytes?.size?.toString() ?: "")
                    val existing = ledger[arg]
                    if (existing != null) {
                        return if (existing.first == canon) ok().apply {
                            putString(JinContract.K_REF, existing.second)
                            putString(JinContract.K_STATUS, JinContract.S_QUEUED)
                        } else err(JinContract.E_TOKEN_REUSED)
                    }
                    val ref = "m${++refCounter}"
                    ledger[arg!!] = canon to ref
                    ok().apply {
                        putString(JinContract.K_REF, ref)
                        putString(JinContract.K_STATUS, JinContract.S_QUEUED)
                    }
                }
                JinContract.M_STATUS -> ok().apply {
                    putString(JinContract.K_STATUS, JinContract.S_SENT_TO_RELAY)
                }
                JinContract.M_POLL -> ok().apply {
                    putString(JinContract.K_ITEMS, inboxJson)
                    putString(JinContract.K_CURSOR, inboxCursor)
                }
                JinContract.M_LIST_CHATS -> ok().apply {
                    putString(JinContract.K_ITEMS, listChatsJson)
                    putString(JinContract.K_CURSOR, "")
                }
                else -> err(JinContract.E_UNKNOWN_METHOD)
            }
        }

        override fun openWrite(uri: Uri): ParcelFileDescriptor? {
            val token = uri.pathSegments.last()
            val pipe = ParcelFileDescriptor.createPipe()
            val tmp = File.createTempFile("fake-stage", ".bin")
            tmp.deleteOnExit()
            Thread {
                FileInputStream(pipe[0].fileDescriptor).use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out) }
                }
                stagedBytes[token] = tmp.readBytes()
            }.start()
            return pipe[1]
        }

        private fun ok() = Bundle().apply { putBoolean(JinContract.K_OK, true) }
        private fun err(code: String) = Bundle().apply {
            putBoolean(JinContract.K_OK, false)
            putString(JinContract.K_ERROR, code)
        }
    }

    private fun tmpFile(bytes: ByteArray): File {
        val f = File.createTempFile("jinclient-test", ".mp4")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun capabilitiesParsesGrantAndLimits() {
        val client = JinClient(FakeJin(granted = true))
        val caps = client.capabilities()
        assertEquals(1, caps.version)
        assertEquals(JinContract.DEFAULT_MAX_BYTES, caps.maxBytes)
        assertTrue(caps.granted)
        assertEquals("/bidik", caps.prefix)
    }

    @Test
    fun capabilitiesWithoutGrantIsNotAnError() {
        val client = JinClient(FakeJin(granted = false))
        val caps = client.capabilities()
        assertFalse(caps.granted)
        assertEquals(null, caps.prefix)
    }

    @Test
    fun grantedCallsFailClosedWhenNotGranted() {
        val client = JinClient(FakeJin(granted = false))
        for (call in listOf<(JinClient) -> Unit>(
            { it.listChats() },
            { it.sendText(it.newToken(), "h", "hi") },
            { it.poll("0") },
            { it.status("m1") }
        )) {
            try {
                call(client)
                fail("expected NOT_AUTHORIZED")
            } catch (e: JinException) {
                assertEquals(JinContract.E_NOT_AUTHORIZED, e.code)
            }
        }
    }

    @Test
    fun listChatsParsesHandlesAndNames() {
        val fake = FakeJin()
        fake.listChatsJson = """[{"handle":"aGFuZGxl","name":"Family"}]"""
        val chats = JinClient(fake).listChats()
        assertEquals(1, chats.size)
        assertEquals("aGFuZGxl", chats[0].handle)
        assertEquals("Family", chats[0].name)
    }

    @Test
    fun sendTextBuildsCanonicalMetaAndReturnsRef() {
        val fake = FakeJin()
        val client = JinClient(fake)
        val token = client.newToken()
        val before = System.currentTimeMillis()
        val res = client.sendText(token, "handle-1", "hello")
        assertEquals("m1", res.ref)
        assertEquals(JinContract.S_QUEUED, res.status)
        assertEquals(JinContract.M_COMMIT, fake.lastMethod)
        val meta = fake.lastMeta!!
        assertEquals("handle-1", meta.getString("chat"))
        assertEquals("hello", meta.getString("text"))
        assertTrue(meta.getLong("ts") >= before)
    }

    @Test
    fun newTokensMatchTheContractPattern() {
        val client = JinClient(FakeJin())
        repeat(20) {
            assertTrue(JinContract.TOKEN_REGEX.matches(client.newToken()))
        }
    }

    @Test
    fun retryWithSameTokenAndBodyReturnsOriginalRef() {
        val fake = FakeJin()
        val client = JinClient(fake)
        val token = client.newToken()
        val first = client.sendText(token, "h", "same body")
        val second = client.sendText(token, "h", "same body")
        assertEquals(first.ref, second.ref)
    }

    @Test
    fun tokenReuseWithDifferentBodyFailsClosed() {
        val client = JinClient(FakeJin())
        val token = client.newToken()
        client.sendText(token, "h", "one")
        try {
            client.sendText(token, "h", "two")
            fail("expected TOKEN_REUSED")
        } catch (e: JinException) {
            assertEquals(JinContract.E_TOKEN_REUSED, e.code)
        }
    }

    @Test
    fun sendFileStagesBytesThenCommits() {
        val fake = FakeJin()
        val client = JinClient(fake)
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val res = client.sendFile(
            client.newToken(), "h", "clip", tmpFile(payload), "clip.mp4", "video/mp4"
        )
        assertEquals("m1", res.ref)
        val meta = fake.lastMeta!!
        assertEquals("clip.mp4", meta.getString("filename"))
        assertEquals("video/mp4", meta.getString("mime"))
        // The staged bytes reached the fake provider intact (drain is async).
        val deadline = System.currentTimeMillis() + 5_000
        while (fake.stagedBytes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val staged = fake.stagedBytes.values.single()
        assertTrue(staged.contentEquals(payload))
    }

    @Test
    fun oversizedFileIsRefusedBeforeStaging() {
        val fake = FakeJin()
        val client = JinClient(fake)
        val big = tmpFile(ByteArray(1024))
        try {
            client.sendFile(client.newToken(), "h", "", big, "b.bin", "application/octet-stream", maxBytes = 512)
            fail("expected FILE_TOO_LARGE")
        } catch (e: JinException) {
            assertEquals(JinContract.E_FILE_TOO_LARGE, e.code)
        }
        assertTrue(fake.stagedBytes.isEmpty())
    }

    @Test
    fun failedCommitCancelsTheStage() {
        val fake = FakeJin()
        val client = JinClient(fake)
        val token = client.newToken()
        // Commit fails (bot down after staging) -> stage must be cancelled.
        fake.botRunning = false
        try {
            client.sendFile(token, "h", "", tmpFile(ByteArray(10)), "b.bin", "application/octet-stream")
            fail("expected BOT_NOT_RUNNING")
        } catch (e: JinException) {
            assertEquals(JinContract.E_BOT_NOT_RUNNING, e.code)
        }
        assertTrue(token in fake.cancelled)
    }

    @Test
    fun badTokensAreRejectedClientSide() {
        val client = JinClient(FakeJin())
        for (bad in listOf("", "short", "has space in it", "x".repeat(65))) {
            try {
                client.sendText(bad, "h", "hi")
                fail("expected BAD_TOKEN for '$bad'")
            } catch (e: JinException) {
                assertEquals(JinContract.E_BAD_TOKEN, e.code)
            }
        }
    }

    @Test
    fun pollParsesItemsAndCursor() {
        val fake = FakeJin()
        fake.inboxJson = """[{"seq":3,"ts":99,"chat":"h1","from":"a@b.c","text":"/bidik record"}]"""
        fake.inboxCursor = "3"
        val res = JinClient(fake).poll("0")
        assertEquals("3", res.cursor)
        assertEquals(1, res.items.size)
        val item = res.items[0]
        assertEquals(3L, item.seq)
        assertEquals("h1", item.chat)
        assertEquals("a@b.c", item.from)
        assertEquals("/bidik record", item.text)
    }

    @Test
    fun statusParsesStateAndReason() {
        val st = JinClient(FakeJin()).status("m1")
        assertEquals(JinContract.S_SENT_TO_RELAY, st.status)
    }
}
