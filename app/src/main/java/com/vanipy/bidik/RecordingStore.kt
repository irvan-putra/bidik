package com.vanipy.bidik

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local store for screen recordings.
 *
 * Recordings are MP4 files in the app-private files dir (`recordings/`).
 * They are kept, never auto-deleted, and leave the phone only when the
 * user shares one explicitly from the app's list. They are gone when the
 * app's data is cleared.
 *
 * The "active" marker lets a reopened activity show the stop control while
 * a recording runs in the foreground service; it is a file name, never
 * content.
 */
object RecordingStore {
    private const val DIR = "recordings"
    private const val PREFS = "recording_state"
    private const val KEY_ACTIVE = "active"

    /** Maximum length of one recording; the service stops at this point. */
    const val MAX_DURATION_MS = 10L * 60L * 1000L

    /** Minimum free space required to start a recording (bytes). */
    const val MIN_FREE_BYTES = 200L * 1024L * 1024L

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** Fresh, non-colliding file for a new recording. */
    fun newRecordingFile(context: Context, now: Date = Date()): File {
        val dir = dir(context)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        var candidate = File(dir, "bidik-$stamp.mp4")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "bidik-$stamp-$suffix.mp4")
            suffix++
        }
        return candidate
    }

    /** Saved recordings, newest first. Non-MP4 leftovers are ignored. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Everything the user can share: recordings and exported GIFs. */
    fun listSharable(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && (f.extension == "mp4" || f.extension == "gif") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Fresh, non-colliding GIF file for a new export. */
    fun newGifFile(context: Context, now: Date = Date()): File {
        val dir = dir(context)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        var candidate = File(dir, "bidik-$stamp.gif")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "bidik-$stamp-$suffix.gif")
            suffix++
        }
        return candidate
    }

    fun setActive(context: Context, fileName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, fileName).apply()
    }

    fun activeName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)

    fun clearActive(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_ACTIVE).apply()
    }

    /** Bounded free-space check so a recording cannot fill the phone. */
    fun hasFreeSpace(context: Context, dir: File = dir(context)): Boolean {
        val target = if (dir.exists()) dir else context.filesDir
        return target.usableSpace >= MIN_FREE_BYTES
    }

    /**
     * H.264 bit rate for a given frame size, capped so a long recording
     * stays a bounded size. Roughly 5 bits per pixel at 30fps.
     */
    fun videoBitRate(width: Int, height: Int): Int {
        val raw = width.toLong() * height.toLong() * 5L
        return raw.coerceIn(1_000_000L, 20_000_000L).toInt()
    }

    /** Small JSON summary for status lines; never content. */
    fun summary(context: Context): String {
        val files = list(context)
        val bytes = files.sumOf { it.length() }
        return JSONObject()
            .put("count", files.size)
            .put("bytes", bytes)
            .toString()
    }
}
