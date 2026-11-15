package com.vanipy.mata

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.FileOutputStream

/**
 * Pixel proof for the opened Diagnostics dialog and a boundary proof for
 * the export: the share must carry a content:// URI from our own
 * FileProvider with a read grant, never a file path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsUiTest {

    @Test
    fun dialogRendersRecentLogEntries() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(app.filesDir, "logs").deleteRecursively()
        DiagLog.log(app, "account", "create_failed", ms = 8123, detail = "category=dns_resolution_failed")
        DiagLog.log(app, "capture", "failed", detail = "page_index=1 of 2 category=page_load_failed_or_timed_out")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        Thread.sleep(1000)
        activity.showDiagnostics()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("diagnostics dialog should be showing", dialog)
        val root = dialog!!.window!!.decorView
        val w = 540
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = root.measuredHeight
        assertTrue("dialog content suspiciously short: $h", h > 100)
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        root.draw(Canvas(bmp))
        val out = File("/tmp/mata-diagnostics-dialog.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("render missing: $out", out.exists() && out.length() > 5_000)
    }

    @Test
    fun shareExportsOnlyCacheSnapshotViaProviderUri() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(app.filesDir, "logs").deleteRecursively()
        DiagLog.log(app, "run", "end", ms = 42, detail = "ok=2 failed=0")
        DiagLog.log(app, "capture", "failed", detail = "category=dns_resolution_failed https://internal.example.test/private?id=page-123")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.shareDiagnostics()
        val chooser = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull("a chooser should have been started", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("com.vanipy.mata.fileprovider", uri.authority)
        assertTrue(
            "read grant flag required",
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        // The exported bytes are a snapshot in the app cache, the only
        // path the FileProvider exposes (see res/xml/file_paths.xml).
        val exported = File(activity.cacheDir, "diagnostics/mata-diagnostics.txt")
        assertTrue(exported.exists())
        val exportText = exported.readText()
        assertTrue(exportText.contains("\"event\":\"end\""))
        assertTrue(exportText.contains("[details omitted]"))
        assertTrue(!exportText.contains("internal.example.test"))
        assertTrue(!exportText.contains("page-123"))
    }
}
