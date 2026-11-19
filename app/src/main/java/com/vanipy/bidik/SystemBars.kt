package com.vanipy.bidik

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Status-bar and navigation-bar heights in pixels on the default
 * display. Used to crop those bands out of recordings when the "system
 * bars: hidden" option is on, and to keep them out of loop matching.
 *
 * Source: the default display's window metrics on API 30+, the classic
 * `status_bar_height` / `navigation_bar_height` resources below it.
 * Values are rounded down to even numbers because the H.264 encoder
 * needs an even frame height. Either band can legitimately be 0 (for
 * example gesture navigation with no bar), which crops nothing.
 */
object SystemBars {

    class Bands(val topPx: Int, val bottomPx: Int)

    fun bands(context: Context): Bands {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val insets = wm.currentWindowMetrics.windowInsets
                // Rotation-aware by construction: in landscape the nav
                // bar sits on a side, so its bottom inset is 0 and no
                // real content is cut. Gesture navigation reports its
                // own small bottom inset (or none) the same way.
                val top = insets.getInsets(WindowInsets.Type.statusBars()).top
                val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                return Bands(even(top), even(bottom))
            } catch (_: Exception) {
                // fall through to the legacy resource path
            }
        }
        // Legacy path (pre-API 30 or a metrics failure). The dimens are
        // portrait values; in landscape the nav bar moves to a side, so
        // skip the bottom band rather than crop real content.
        val top = resourceDimen(context, "status_bar_height")
        val bottom = if (rotation(context) == Surface.ROTATION_0) {
            resourceDimen(context, "navigation_bar_height")
        } else {
            0
        }
        return Bands(even(top), even(bottom))
    }

    private fun rotation(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }
    }

    private fun resourceDimen(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        if (id == 0) return 0
        return try {
            context.resources.getDimensionPixelSize(id)
        } catch (_: Exception) {
            0
        }
    }

    private fun even(v: Int): Int = if (v < 0) 0 else v - (v % 2)
}
