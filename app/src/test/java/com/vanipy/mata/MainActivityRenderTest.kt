package com.vanipy.mata

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders MainActivity with real Skia (Robolectric native graphics) at
 * phone-width portrait and writes a PNG for pixel inspection - the same
 * no-device verification pattern as intodo's render-check. CI uploads the
 * PNG as a run artifact.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityRenderTest {

    @Test
    fun rendersQueueListAtPhoneWidth() {
        // Seed a local queue BEFORE the activity is created so the first
        // refresh shows content.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(app.filesDir, "capture-queue.json").writeText(
            """{"type":"capture-queue","v":1,"pages":[
              {"id":"vana-home","url":"https://example.com/vana/"},
              {"id":"vana-c1","url":"https://example.com/vana/#c1","full_page":false}
            ]}"""
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        // Let the refresh coroutine populate the list.
        Thread.sleep(1500)
        val root = activity.window.decorView
        val w = 1080 / 2 // 360dp at 2x density, typical phone width
        val h = 2400 / 2
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File("/tmp/mata-main-activity.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("render missing: $out", out.exists() && out.length() > 5_000)
    }
}
