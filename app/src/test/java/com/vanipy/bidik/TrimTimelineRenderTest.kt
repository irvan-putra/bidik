package com.vanipy.bidik

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the TikTok-style trim strip with real Skia against a fake
 * frame source: color-banded synthetic "frames" so the thumbnails are
 * visually distinct, then the selection moved so the dim overlay and
 * handles show. The PNGs land in the unit-test-results artifact for
 * pixel inspection - the same no-device pattern as MainActivityRenderTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrimTimelineRenderTest {

    /** Synthetic frames: a horizontal color ramp keyed to the requested
     *  time so adjacent slots differ visibly. */
    private class FakeSource(val duration: Long) : FrameSource {
        val calls = ArrayList<Long>()
        override fun durationMs(): Long = duration
        override fun frameAt(timeUs: Long, maxWidthPx: Int): Bitmap {
            calls.add(timeUs)
            val t = (timeUs / 1000L).toInt()
            val bmp = Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb((t * 37) % 256, (t * 91) % 256, (t * 53) % 256))
            val p = android.graphics.Paint().apply {
                color = Color.rgb((t * 53 + 128) % 256, (t * 37 + 128) % 256, (t * 91 + 128) % 256)
            }
            c.drawRect(10f, 10f, 80f, (20 + t % 120).toFloat(), p)
            return bmp
        }
        override fun close() {}
    }

    private fun render(view: View, w: Int, h: Int, out: File) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("render missing: $out", out.exists() && out.length() > 500)
    }

    @Test
    fun stripShowsFramesHandlesAndDimming() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val view = TrimTimelineView(app)
        val source = FakeSource(6000)
        view.setSource(source)

        // Thumbnails load on a background thread: lay out (which starts
        // the loader), then wait until the slots land before drawing.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 540, 96)
        val deadline = System.currentTimeMillis() + 10_000
        while (view.loadedSlots() == 0 && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        assertTrue("thumbnails never loaded", view.loadedSlots() > 0)

        // Whole-recording selection first: no dimming, handles at the edges.
        render(view, 540, 96, File("build/reports/bidik-trim-full.png"))

        // Thumbnails were requested through the source (async loader may
        // still be running; the loader path is exercised either way).
        assertTrue("no frames requested", source.calls.isNotEmpty())

        // A picked span: dim overlay outside, handles inside.
        var notified: Triple<Long, Long, Boolean>? = null
        view.listener = { s, e, scrubbing, _ -> notified = Triple(s, e, scrubbing) }
        view.setSpan(1000, 3000)
        assertEquals(Triple(1000L, 3000L, false), notified)
        render(view, 540, 96, File("build/reports/bidik-trim-span.png"))
    }

    @Test
    fun dragTargetsFollowTheHandles() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val view = TrimTimelineView(app)
        view.setSource(FakeSource(6000))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 600, 96)
        view.setSpan(1200, 4800) // handles at x=120 and x=480 of 600
        assertEquals(TrimTimelineView.DRAG_START, view.dragTargetFor(118f))
        assertEquals(TrimTimelineView.DRAG_END, view.dragTargetFor(482f))
        assertEquals(TrimTimelineView.DRAG_SCRUB, view.dragTargetFor(300f))
    }
}
