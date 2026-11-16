package com.vanipy.bidik

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Account-free DeltaChat runtime smoke: proves the vendored arm64 rpc
 * core (librpcsrv.so) loads and answers an RPC on THIS Android runtime.
 * On the x86_64 CI emulator this exercises the image's ARM translation;
 * on a physical arm64 phone it runs natively. No account is created, no
 * network is used, no keys exist: the python side uses a throwaway
 * accounts dir it removes afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DcRuntimeSmokeTest {

    @Test(timeout = 180_000)
    fun vendoredRpcCoreStartsAndAnswers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val server = File(ctx.applicationInfo.nativeLibraryDir, "librpcsrv.so")
        assertTrue(
            "rpc server binary not packaged (expected in the non-ciEmulator build): ${server.absolutePath}",
            server.exists() && server.length() > 0
        )
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        val raw = Python.getInstance().getModule("dc_transport")
            .callAttr(
                "runtime_smoke",
                ctx.applicationInfo.nativeLibraryDir,
                ctx.filesDir.absolutePath
            )
            .toString()
        val res = JSONObject(raw)
        assertEquals("ok", res.getString("status"))
        // Fresh throwaway dir: no accounts may exist there.
        assertEquals(0, res.getInt("accounts_seen"))
    }
}
