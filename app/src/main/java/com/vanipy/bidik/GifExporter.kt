package com.vanipy.bidik

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Exports a span of a saved recording to a looping GIF.
 *
 * The source MP4 is never modified. Frames are decoded at [fps],
 * scaled so the width is at most [maxWidth] px, mapped onto the shared
 * 216-colour palette with dithering, and written with the infinite-loop
 * marker. The result lands next to the recordings (app-private files
 * dir) and leaves the phone only through the same manual share action
 * as a video.
 *
 * A span longer than [MAX_EXPORT_MS] is refused: GIF has no real
 * compression for long video, and a multi-minute GIF would be hundreds
 * of megabytes.
 */
object GifExporter {

    const val MAX_EXPORT_MS = 20_000L
    const val DEFAULT_FPS = 10
    const val DEFAULT_MAX_WIDTH = 480

    class Outcome(
        val frames: Int,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val durationMs: Long
    )

    fun export(
        src: File,
        startMs: Long,
        endMs: Long,
        dst: File,
        fps: Int = DEFAULT_FPS,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Outcome {
        require(startMs >= 0) { "start must be 0s or later" }
        require(endMs > startMs) { "end must be after start" }
        require(endMs - startMs <= MAX_EXPORT_MS) {
            "a GIF export is capped at ${MAX_EXPORT_MS / 1000}s"
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(src.absolutePath)
            val videoMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalArgumentException("could not read the recording's duration")
            require(startMs < videoMs) { "start is past the end of the recording" }
            val stopMs = minOf(endMs, videoMs)
            val stepMs = 1000L / fps
            val total = (((stopMs - startMs) + stepMs - 1) / stepMs).toInt()
            val frames = ArrayList<ByteArray>(total)
            var outW = 0
            var outH = 0
            var t = startMs
            var done = 0
            while (t < stopMs) {
                val bitmap = retriever.getFrameAtTime(
                    t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: throw IllegalStateException("could not decode a frame at ${t}ms")
                val scaled = if (bitmap.width > maxWidth) {
                    val h = bitmap.height.toLong() * maxWidth / bitmap.width
                    Bitmap.createScaledBitmap(bitmap, maxWidth, h.toInt().coerceAtLeast(1), true)
                } else {
                    bitmap
                }
                if (outW == 0) {
                    outW = scaled.width
                    outH = scaled.height
                }
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                frames.add(GifEncoder.mapToPalette(pixels, scaled.width, scaled.height))
                if (scaled != bitmap) scaled.recycle()
                bitmap.recycle()
                done++
                onProgress?.invoke(done, total)
                t += stepMs
            }
            require(frames.isNotEmpty()) { "no frames decoded in the chosen span" }
            dst.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(dst)).use { stream ->
                GifEncoder.write(
                    stream, outW, outH, frames,
                    delayCs = (100 / fps).coerceAtLeast(1), loop = true
                )
            }
            return Outcome(frames.size, outW, outH, dst.length(), stopMs - startMs)
        } finally {
            retriever.release()
        }
    }
}
