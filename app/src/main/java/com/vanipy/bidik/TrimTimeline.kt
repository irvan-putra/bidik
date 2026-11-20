package com.vanipy.bidik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * TikTok-style frame strip for picking a start/end span inside a
 * recording: thumbnails across the whole clip, a draggable handle at
 * each end of the selection, and scrub anywhere else to inspect a
 * frame. The auto-detected loop candidates still exist; they simply
 * fill these handles instead of a text field.
 *
 * [TrimSelection] holds the pure span math (clamping, ordering, the
 * minimum span) so host tests cover it without a device; the view is a
 * thin renderer over it plus a thumbnail cache.
 */
class TrimSelection(val durationMs: Long) {
    var startMs = 0L
        private set
    var endMs = durationMs
        private set

    init {
        require(durationMs > 0) { "duration must be positive" }
    }

    /** Smallest selectable span; shorter exports are never useful. */
    fun minSpanMs(): Long = min(MIN_SPAN_MS, durationMs)

    /** Sets both ends at once (loop candidate tap): clamps into
     *  [0, durationMs], orders them, and enforces the minimum span by
     *  growing the range symmetrically around its center. */
    fun setSpan(start: Long, end: Long) {
        var s = start.coerceIn(0, durationMs)
        var e = end.coerceIn(0, durationMs)
        if (s > e) { val t = s; s = e; e = t }
        if (e - s < minSpanMs()) {
            val mid = (s + e) / 2
            val half = minSpanMs() / 2
            s = (mid - half).coerceIn(0, durationMs)
            e = (s + minSpanMs()).coerceIn(0, durationMs)
            s = (e - minSpanMs()).coerceIn(0, durationMs)
        }
        startMs = s
        endMs = e
    }

    /** Moves one end; the moved end may cross the other, which just
     *  swaps their roles like the TikTok handles do. */
    fun moveHandle(isStart: Boolean, timeMs: Long) {
        val t = timeMs.coerceIn(0, durationMs)
        if (isStart) setSpan(t, endMs) else setSpan(startMs, t)
    }

    companion object {
        const val MIN_SPAN_MS = 100L
    }
}

/** Frame provider behind the strip so tests never touch media APIs. */
interface FrameSource {
    fun durationMs(): Long
    /** Returns a frame scaled so its width is at most [maxWidthPx], or
     *  null when the time cannot be decoded. */
    fun frameAt(timeUs: Long, maxWidthPx: Int): Bitmap?
    fun close()
}

/** MediaMetadataRetriever-backed source. OPTION_CLOSEST on purpose:
 *  the default returns only sync frames (1/s keyframes in bidik
 *  recordings), which would make every thumbnail between keyframes a
 *  duplicate and the endpoint frames wrong. */
class RetrieverFrameSource(file: File) : FrameSource {
    private val retriever = MediaMetadataRetriever()
    private val duration: Long

    init {
        retriever.setDataSource(file.absolutePath)
        duration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
    }

    override fun durationMs(): Long = duration

    override fun frameAt(timeUs: Long, maxWidthPx: Int): Bitmap? {
        val full = try {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Exception) {
            null
        } ?: return null
        if (full.width <= maxWidthPx) return full
        val h = max(1, full.height * maxWidthPx / full.width)
        val scaled = Bitmap.createScaledBitmap(full, maxWidthPx, h, true)
        if (scaled != full) full.recycle()
        return scaled
    }

    override fun close() {
        try { retriever.release() } catch (e: Exception) { /* release is best-effort */ }
    }
}

class TrimTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** startMs, endMs, scrubbing, scrubMs after every visible change. */
    var listener: ((Long, Long, Boolean, Long) -> Unit)? = null

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xF5, 0x9E, 0x0B)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xF5, 0x9E, 0x0B)
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x10, 0x18, 0x28)
        strokeWidth = 2f
    }
    private val scrubPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f
    }
    private val placeholderPaint = Paint().apply { color = Color.rgb(0x2A, 0x31, 0x3E) }

    private var source: FrameSource? = null
    private var selection: TrimSelection? = null
    private val thumbs = HashMap<Int, Bitmap>()
    private var slotCount = 0
    private var slotWidthPx = 0f
    private var scrubbing = false
    private var scrubMs = 0L
    private var dragTarget = DRAG_NONE
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var loadGeneration = 0

    fun setSource(source: FrameSource) {
        this.source?.close()
        this.source = source
        this.selection = TrimSelection(source.durationMs())
        thumbs.values.forEach { if (!it.isRecycled) it.recycle() }
        thumbs.clear()
        slotCount = 0
        scrubbing = false
        dragTarget = DRAG_NONE
        notifyChanged()
        requestLayout()
        invalidate()
    }

    fun selection(): TrimSelection? = selection

    /** Loop-candidate tap: fill the handles. */
    fun setSpan(startMs: Long, endMs: Long) {
        selection?.setSpan(startMs, endMs)
        notifyChanged()
        invalidate()
    }

    private fun notifyChanged() {
        val s = selection ?: return
        listener?.invoke(s.startMs, s.endMs, scrubbing, scrubMs)
    }

    // ---- slot math (internal for host tests) ----------------------------

    internal fun computeSlots(widthPx: Int, heightPx: Int): Int {
        val src = source ?: return 0
        if (widthPx <= 0 || heightPx <= 0) return 0
        val first = src.frameAt(0L, heightPx) ?: return 0
        val aspect = if (first.height > 0) first.width.toFloat() / first.height else 1f
        if (!first.isRecycled) first.recycle()
        val slotW = max(1f, heightPx * aspect)
        return max(4, ceil(widthPx / slotW).toInt())
    }

    internal fun timeAtX(x: Float): Long {
        val s = selection ?: return 0L
        if (width <= 0) return 0L
        return (s.durationMs * (x / width).coerceIn(0f, 1f)).toLong()
    }

    internal fun xAtTime(timeMs: Long): Float {
        val s = selection ?: return 0f
        if (s.durationMs <= 0) return 0f
        return width * (timeMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
    }

    /** Which drag target a touch-down at [x] starts: a handle when the
     *  touch lands within its grab threshold, else a scrub. */
    internal fun dragTargetFor(x: Float): Int {
        val s = selection ?: return DRAG_SCRUB
        val threshold = max(handleWidthPx() * 2f, grabThresholdPx())
        return when {
            abs(x - xAtTime(s.startMs)) <= threshold -> DRAG_START
            abs(x - xAtTime(s.endMs)) <= threshold -> DRAG_END
            else -> DRAG_SCRUB
        }
    }

    private fun handleWidthPx(): Float = 12 * resources.displayMetrics.density
    private fun grabThresholdPx(): Float = 24 * resources.displayMetrics.density

    // ---- thumbnail loading ----------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        val src = source ?: return
        if (w <= 0 || h <= 0) return
        val gen = ++loadGeneration
        Thread {
            val slots = computeSlots(w, h)
            if (slots <= 0 || gen != loadGeneration) return@Thread
            val s = selection ?: return@Thread
            val loaded = HashMap<Int, Bitmap>()
            for (i in 0 until slots) {
                if (gen != loadGeneration) return@Thread
                val t = s.durationMs * (2L * i + 1) / (2L * slots)
                val bmp = src.frameAt(t * 1000L, max(1, w * 2 / slots)) ?: continue
                loaded[i] = bmp
            }
            if (gen != loadGeneration) {
                loaded.values.forEach { if (!it.isRecycled) it.recycle() }
                return@Thread
            }
            mainHandler.post {
                if (gen != loadGeneration) {
                    loaded.values.forEach { if (!it.isRecycled) it.recycle() }
                    return@post
                }
                thumbs.values.forEach { if (!it.isRecycled) it.recycle() }
                thumbs.clear()
                thumbs.putAll(loaded)
                slotCount = slots
                slotWidthPx = w.toFloat() / slots
                invalidate()
            }
        }.start()
    }

    // ---- drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = selection ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val slots = if (slotCount > 0) slotCount else 0
        if (slots > 0) {
            for (i in 0 until slots) {
                val left = i * slotWidthPx
                val bmp = thumbs[i]
                if (bmp != null && !bmp.isRecycled) {
                    val scale = h / bmp.height
                    val dw = bmp.width * scale
                    canvas.drawBitmap(bmp, null, RectF(left, 0f, left + dw, h), null)
                } else {
                    canvas.drawRect(left, 0f, left + slotWidthPx, h, placeholderPaint)
                }
            }
        } else {
            canvas.drawRect(0f, 0f, w, h, placeholderPaint)
        }

        val sx = xAtTime(s.startMs)
        val ex = xAtTime(s.endMs)
        if (sx > 0f) canvas.drawRect(0f, 0f, sx, h, dimPaint)
        if (ex < w) canvas.drawRect(ex, 0f, w, h, dimPaint)
        canvas.drawRect(sx, 0f, ex, h, selectionPaint)

        val hw = handleWidthPx()
        canvas.drawRoundRect(RectF(sx - hw / 2, 0f, sx + hw / 2, h), hw / 4, hw / 4, handlePaint)
        canvas.drawRoundRect(RectF(ex - hw / 2, 0f, ex + hw / 2, h), hw / 4, hw / 4, handlePaint)
        canvas.drawLine(sx, h * 0.3f, sx, h * 0.7f, gripPaint)
        canvas.drawLine(ex, h * 0.3f, ex, h * 0.7f, gripPaint)

        if (scrubbing) {
            canvas.drawLine(xAtTime(scrubMs), 0f, xAtTime(scrubMs), h, scrubPaint)
        }
    }

    // ---- touch --------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = selection ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragTarget = dragTargetFor(event.x)
                if (dragTarget == DRAG_SCRUB) {
                    scrubbing = true
                    scrubMs = timeAtX(event.x)
                } else {
                    moveTarget(event.x)
                }
                notifyChanged()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragTarget == DRAG_SCRUB) {
                    if (scrubbing) scrubMs = timeAtX(event.x)
                } else {
                    moveTarget(event.x)
                }
                notifyChanged()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragTarget == DRAG_SCRUB && scrubbing) {
                    // Scrubbing only inspects; it never moves a handle.
                    // The listener sees scrubbing=false and the preview
                    // rests on the start handle's frame again.
                    scrubbing = false
                }
                dragTarget = DRAG_NONE
                notifyChanged()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveTarget(x: Float) {
        val s = selection ?: return
        when (dragTarget) {
            DRAG_START -> s.moveHandle(true, timeAtX(x))
            DRAG_END -> s.moveHandle(false, timeAtX(x))
        }
    }

    fun release() {
        loadGeneration++
        source?.close()
        source = null
        thumbs.values.forEach { if (!it.isRecycled) it.recycle() }
        thumbs.clear()
    }

    companion object {
        const val DRAG_NONE = 0
        const val DRAG_START = 1
        const val DRAG_END = 2
        const val DRAG_SCRUB = 3
    }
}
