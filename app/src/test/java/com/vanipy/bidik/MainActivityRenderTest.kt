package com.vanipy.bidik

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
        val out = File("/tmp/bidik-main-activity.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("render missing: $out", out.exists() && out.length() > 5_000)

        assertTrue("Record screen button must be present", findButton(root, "Record screen"))
        assertTrue("Run capture button must be present", findButton(root, "Run capture"))
        assertTrue("Menu button must be present", findButton(root, "Menu"))
        // The drawer is part of the hierarchy even while closed.
        assertTrue("Diagnostics must stay reachable in the menu", findButton(root, "Diagnostics"))
        // Fresh install: the path into messaging setup must be visible
        // on the main screen without opening the menu first.
        assertTrue(
            "setup hint must be visible while unpaired",
            findButton(root, "Set up messaging sync (in the menu)")
        )
        // The menu must start closed: home screen is the everyday view.
        assertTrue(
            "menu must start closed",
            !activity.drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)
        )
    }

    @Test
    fun menuOpensToSetupAndDiagnostics() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        Thread.sleep(1500)
        val root = activity.window.decorView
        val w = 1080 / 2
        val h = 2400 / 2
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)
        // DrawerLayout tracks open state only after a layout pass, and
        // Robolectric never ticks the drag animation, so open without
        // animating.
        activity.drawer.openDrawer(androidx.core.view.GravityCompat.START, false)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)
        assertTrue(
            "menu did not open",
            activity.drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)
        )
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        root.draw(Canvas(bmp))
        val out = File("/tmp/bidik-menu-open.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("render missing: $out", out.exists() && out.length() > 5_000)
        assertTrue("Diagnostics must be in the menu", findButton(root, "Diagnostics"))
    }

    companion object {
        fun findButton(v: View, label: String): Boolean {
            if (v is android.widget.Button && v.text.toString() == label) return true
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    if (findButton(v.getChildAt(i), label)) return true
                }
            }
            return false
        }
    }

    @Test
    fun setupContentIsScrollableAndFullyLaidOut() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        Thread.sleep(1500)
        // The setup box must live inside a ScrollView: its content
        // (account, pairing, retention notes) can exceed the leftover
        // screen on small phones and would otherwise be unreachable.
        assertTrue(
            "setupBox must be wrapped in a ScrollView",
            activity.setupBox.parent is android.widget.ScrollView
        )
        // Lay the setup content out at its full height and render it,
        // proving every row - including the retention note at the
        // bottom - is reachable by scrolling.
        val box = activity.setupBox
        val w = 540
        box.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = box.measuredHeight
        assertTrue("setup content suspiciously short: $h", h > 100)
        // The retention note must be the LAST row so it is present in the
        // unprovisioned state (the branch returns before pairing views).
        val last = box.getChildAt(box.childCount - 1)
        assertTrue(
            "last setup row should be the retention note",
            last is android.widget.TextView &&
                last.text.contains("archived per run")
        )
        box.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        box.draw(Canvas(bmp))
        val out = File("/tmp/bidik-setup-full.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("render missing: $out", out.exists() && out.length() > 5_000)
    }
}
