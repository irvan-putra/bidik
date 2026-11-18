package com.vanipy.bidik

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gap-filling H.264 screen capture pipeline.
 *
 * A VirtualDisplay renders the screen into a SurfaceTexture. A render
 * loop on a dedicated thread copies each frame - or, when the screen is
 * still, the previous frame again - onto the MediaCodec input surface
 * with an explicit wall-clock presentation timestamp
 * (eglPresentationTimeANDROID). This is the established pattern for
 * surface-fed encoders (Grafika / bigflake): the codec emits exactly the
 * frames it is fed, at the timestamps they carry. Feeding the encoder
 * directly from the display instead (the old MediaRecorder path) means a
 * still screen produces no samples: elapsed time compresses and a still
 * tail at stop is truncated (a 6s still recording came out as 1.7s).
 * Fixed-cadence feeding keeps the video timeline equal to real elapsed
 * time through motionless stretches, including the final moment.
 *
 * Encoded samples go to a MediaMuxer (MP4). No audio track.
 *
 * Threading: everything GL/codec lives on one private thread. prepare,
 * startFeeding and stop marshal onto it and block briefly for the
 * caller.
 */
internal class CaptureEngine(
    private val width: Int,
    private val height: Int,
    private val outFile: File
) {
    private val thread = HandlerThread("bidik-capture")
    private lateinit var handler: Handler
    private val frameAvailable = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var codecInput: Surface? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var surfaceTexture: SurfaceTexture? = null

    /** Surface handed to MediaProjection.createVirtualDisplay. */
    lateinit var displaySurface: Surface
        private set

    private var texId = 0
    private var program = 0
    private var aPos = -1
    private var aTex = -1
    private var uTexMatrix = -1
    private val texMatrix = FloatArray(16)
    private var haveTexture = false
    private var feeding = false
    private var released = false
    private var framesWritten = 0L
    private var startNanos = -1L

    private val vertexBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texBuffer = floatBufferOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private val tick = object : Runnable {
        override fun run() {
            if (!feeding) return
            step()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    /**
     * Starts the capture thread, configures and starts the encoder and
     * muxer and builds the GL pipeline. Nothing is fed until
     * [startFeeding]. Throws on setup failure.
     */
    fun prepare() {
        thread.start()
        handler = Handler(thread.looper)
        await { setup() }
    }

    /** Begins feeding frames at a fixed cadence. */
    fun startFeeding() {
        await {
            feeding = true
            handler.post(tick)
        }
    }

    /**
     * Stops feeding, draws one final frame stamped at the stop moment so
     * a still tail is preserved, drains the encoder to end-of-stream and
     * releases everything. Returns true only when [save] asked for the
     * file and the muxer produced a playable MP4 (at least one frame
     * written, clean EOS and muxer stop).
     */
    fun stop(save: Boolean, timeoutMs: Long = 10_000): Boolean {
        var usable = false
        val finished = await(timeoutMs, propagate = false) {
            usable = stopOnThread(save)
        }
        thread.quitSafely()
        return finished && usable
    }

    private fun setup() {
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, RecordingStore.videoBitRate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MIME_TYPE)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = enc.createInputSurface()
        codecInput = input
        setupGl(input)
        enc.start()
        encoder = enc

        surfaceTexture = SurfaceTexture(texId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ frameAvailable.set(true) }, handler)
        }
        displaySurface = Surface(surfaceTexture)
    }

    private fun step() {
        drainEncoder(expectEos = false)
        if (frameAvailable.getAndSet(false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
                haveTexture = true
            } catch (_: Exception) {
                // Out-of-sync texture: keep showing the last frame.
            }
        }
        drawNow()
    }

    /** Renders the current texture to the encoder with a wall-clock PTS. */
    private fun drawNow() {
        if (!haveTexture) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        val now = System.nanoTime()
        if (startNanos < 0) startNanos = now
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, now - startNanos)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun stopOnThread(save: Boolean): Boolean {
        feeding = false
        handler.removeCallbacks(tick)
        if (released) return false
        released = true
        drawNow()
        var sawEos = false
        try {
            encoder?.signalEndOfInputStream()
            sawEos = drainEncoder(expectEos = true)
        } catch (_: Exception) {
        }
        val wroteFrames = framesWritten > 0
        var muxerOk = false
        if (muxerStarted) {
            try {
                muxer?.stop()
                muxerOk = true
            } catch (_: Exception) {
            }
        }
        releaseAll()
        return save && sawEos && wroteFrames && muxerOk
    }

    /** Returns true when end-of-stream was seen (only asked when expected). */
    private fun drainEncoder(expectEos: Boolean): Boolean {
        val enc = encoder ?: return false
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NS
        while (true) {
            val index = enc.dequeueOutputBuffer(info, if (expectEos) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!expectEos) return false
                    if (System.nanoTime() > deadline) return false
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val mux = muxer ?: return false
                    trackIndex = mux.addTrack(enc.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = enc.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        muxerStarted
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, buffer, info)
                        framesWritten++
                    }
                    enc.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun releaseAll() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        try {
            codecInput?.release()
        } catch (_: Exception) {
        }
        codecInput = null
        if (::displaySurface.isInitialized) {
            try {
                displaySurface.release()
            } catch (_: Exception) {
            }
        }
        try {
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
        surfaceTexture = null
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
    }

    private fun setupGl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "no recordable EGL config" }
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
        GLES20.glViewport(0, 0, width, height)

        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
    }

    private fun buildProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertex)
        GLES20.glAttachShader(prog, fragment)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "shader link failed: " + GLES20.glGetProgramInfoLog(prog) }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    /** Runs [block] on the capture thread and waits for it. */
    private fun await(
        timeoutMs: Long = 5_000,
        propagate: Boolean = true,
        block: () -> Unit
    ): Boolean {
        val latch = CountDownLatch(1)
        var failure: RuntimeException? = null
        handler.post {
            try {
                block()
            } catch (e: RuntimeException) {
                failure = e
            } finally {
                latch.countDown()
            }
        }
        val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (propagate) failure?.let { throw it }
        return finished
    }

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 30
        private const val FRAME_INTERVAL_MS = 33L
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val DRAIN_TIMEOUT_NS = 3_000_000_000L

        private const val VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
                "attribute vec4 aPos;\n" +
                "attribute vec4 aTex;\n" +
                "varying vec2 vTex;\n" +
                "void main() {\n" +
                "    gl_Position = aPos;\n" +
                "    vTex = (uTexMatrix * aTex).xy;\n" +
                "}\n"

        private const val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTex;\n" +
                "uniform samplerExternalOES sTex;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(sTex, vTex);\n" +
                "}\n"

        private fun floatBufferOf(vararg values: Float): ByteBuffer {
            val bytes = ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
            bytes.asFloatBuffer().put(values)
            bytes.position(0)
            return bytes
        }
    }
}
