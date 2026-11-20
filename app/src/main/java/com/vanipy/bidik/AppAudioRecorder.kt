package com.vanipy.bidik

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional audio side of a screen recording: internal app sound
 * (AudioPlaybackCapture, Android 10+) and/or the microphone, mixed into
 * one AAC track in the same MP4 as the video.
 *
 * Consent and permission: app-sound capture rides the same
 * MediaProjection consent the video already has; both sources require
 * the RECORD_AUDIO runtime permission (Android's rule for any
 * AudioRecord-based capture, playback capture included - the microphone
 * source is only ever opened when the mic option is on). The caller
 * checks the grant before constructing this.
 *
 * What can be captured: only audio whose player sets usage MEDIA, GAME
 * or UNKNOWN and whose app has not opted out of playback capture. An
 * app can refuse (music/streaming apps often do); a refused source then
 * contributes silence, which only a runtime check of the output can
 * detect, so the evidence run asserts non-silent samples rather than
 * assuming capturability from source inspection.
 *
 * Mixing: game sound arrives stereo, the mic mono. When both are on
 * they are summed per channel with clipping clamped into a single
 * track, because most players only play the first audio track of an
 * MP4; two separate tracks would silently lose one source.
 *
 * Timestamps: audio presentation times sit on the video's clock -
 * relative to the video track's first frame (CaptureEngine's wall-clock
 * base) - so the tracks start aligned even with a start delay.
 *
 * Threading: one capture thread reads the sources, mixes, feeds the
 * encoder and drains it into the shared muxer through
 * [CaptureEngine.MuxSink], which serializes writes with the video side
 * and holds the muxer start until both tracks exist.
 */
internal class AppAudioRecorder(
    private val projection: MediaProjection?,
    private val micEnabled: Boolean,
    private val sink: CaptureEngine.MuxSink,
    private val videoStartNanos: () -> Long
) {
    init {
        require(projection != null || micEnabled) { "no audio source selected" }
    }

    private val failed = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var samplesWritten = 0L

    private var playbackRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var trackIndex = -1
    private var captureThread: Thread? = null

    /**
     * Builds the audio sources and the AAC encoder. Throws on failure;
     * the caller then records video-only (and says so visibly).
     */
    fun prepare() {
        if (projection != null) {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "playback capture needs Android 10"
            }
            playbackRecord = buildPlaybackRecord(projection)
        }
        if (micEnabled) {
            micRecord = buildMicRecord()
        }
        val format = MediaFormat.createAudioFormat(AAC_MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
        }
        val enc = MediaCodec.createEncoderByType(AAC_MIME)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder = enc
    }

    private fun buildPlaybackRecord(projection: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 2, FRAME_BYTES * 8))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "playback capture refused" }
        return record
    }

    private fun buildMicRecord(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        @Suppress("MissingPermission") // Caller verified the runtime grant.
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min * 2, MIC_FRAME_BYTES * 8)
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "mic capture refused" }
        return record
    }

    /** Starts capturing. Throws if a source fails to start. */
    fun start() {
        val enc = encoder ?: throw IllegalStateException("prepare() first")
        playbackRecord?.startRecording()
        try {
            micRecord?.startRecording()
        } catch (e: Exception) {
            try {
                playbackRecord?.stop()
            } catch (_: Exception) {
            }
            throw e
        }
        enc.start()
        running = true
        captureThread = Thread({ captureLoop() }, "bidik-audio").apply { start() }
    }

    /**
     * Stops the sources, drains the encoder to end-of-stream and lets go
     * of everything. Blocks briefly; called on the capture thread while
     * the muxer is still open. Returns true when at least one audio
     * sample reached the file.
     */
    fun stopDrain(save: Boolean): Boolean {
        running = false
        val thread = captureThread
        if (thread != null) {
            try {
                thread.join(3_000)
            } catch (_: InterruptedException) {
            }
        }
        captureThread = null
        releaseAll()
        return save && samplesWritten > 0 && !failed.get()
    }

    /** Last-resort cleanup when the engine is torn down without a stop. */
    fun releaseQuietly() {
        running = false
        releaseAll()
    }

    private fun captureLoop() {
        val gameBuf = if (playbackRecord != null) ShortArray(FRAMES * 2) else null
        val micBuf = if (micRecord != null) ShortArray(FRAMES) else null
        val mixed = ShortArray(FRAMES * 2)
        var framesEncoded = 0L
        var basePtsUs = -1L
        try {
            while (running) {
                if (gameBuf != null &&
                    !readFully(playbackRecord, gameBuf, gameBuf.size)
                ) {
                    break
                }
                if (micBuf != null &&
                    !readFully(micRecord, micBuf, micBuf.size)
                ) {
                    break
                }
                if (basePtsUs < 0) {
                    // Anchor to the video's first frame so the tracks
                    // start together; wait briefly for it to appear.
                    val videoStart = waitForVideoStart()
                    basePtsUs = (System.nanoTime() - videoStart) / 1_000L
                }
                mixPcm(gameBuf, micBuf, mixed)
                val ptsUs = basePtsUs + framesEncoded * 1_000_000L / SAMPLE_RATE
                if (!encodeChunk(mixed, ptsUs, eos = false)) break
                framesEncoded += FRAMES
                drainOutput(expectEos = false)
            }
            if (!failed.get()) {
                // Signal end of stream at the exact timeline position the
                // audio reached, then drain until the encoder confirms.
                val ptsUs = if (basePtsUs >= 0) {
                    basePtsUs + framesEncoded * 1_000_000L / SAMPLE_RATE
                } else {
                    0L
                }
                encodeChunk(ShortArray(0), ptsUs, eos = true)
                drainOutput(expectEos = true)
            }
        } catch (_: Exception) {
            // Mid-capture failure: the video continues unaffected; the
            // service reports the missing sound through stopDrain.
            failed.set(true)
        }
    }

    /** Waits up to 2s for the video track's first-frame timestamp. */
    private fun waitForVideoStart(): Long {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            val start = videoStartNanos()
            if (start > 0) return start
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return System.nanoTime()
            }
        }
        return System.nanoTime()
    }

    private fun readFully(record: AudioRecord?, out: ShortArray, count: Int): Boolean {
        if (record == null) return false
        var offset = 0
        val deadline = System.nanoTime() + 500_000_000L
        while (offset < count) {
            val got = record.read(out, offset, count - offset)
            if (got <= 0) {
                if (System.nanoTime() > deadline) {
                    failed.set(true)
                    return false
                }
                continue
            }
            offset += got
        }
        return true
    }



    /** Queues one input buffer; false when the encoder is done or broken. */
    private fun encodeChunk(pcm: ShortArray, ptsUs: Long, eos: Boolean): Boolean {
        val enc = encoder ?: return false
        val index = enc.dequeueInputBuffer(10_000L)
        if (index < 0) return !eos
        val buffer = enc.getInputBuffer(index) ?: return false
        buffer.clear()
        val bytes = pcm.size * 2
        if (bytes > 0) {
            if (buffer.capacity() < bytes) {
                failed.set(true)
                return false
            }
            for (value in pcm) {
                buffer.put((value.toInt() and 0xFF).toByte())
                buffer.put((value.toInt() shr 8 and 0xFF).toByte())
            }
        }
        enc.queueInputBuffer(
            index, 0, bytes, ptsUs,
            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        )
        return true
    }

    private fun drainOutput(expectEos: Boolean): Boolean {
        val enc = encoder ?: return false
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NS
        while (true) {
            val index = enc.dequeueOutputBuffer(info, if (expectEos) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!expectEos) return true
                    if (System.nanoTime() > deadline) {
                        failed.set(true)
                        return false
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = sink.muxAddTrack(enc.outputFormat)
                }
                index >= 0 -> {
                    val buffer: ByteBuffer? = enc.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        if (sink.muxWriteSample(trackIndex, buffer, info)) {
                            samplesWritten++
                        }
                    }
                    enc.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun releaseAll() {
        for (record in listOf(playbackRecord, micRecord)) {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            try {
                record?.release()
            } catch (_: Exception) {
            }
        }
        playbackRecord = null
        micRecord = null
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
    }

    companion object {
        private const val AAC_MIME = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val AAC_BIT_RATE = 128_000
        /** Frames read and mixed per iteration (~23ms at 44.1kHz). */
        private const val FRAMES = 1_024
        private const val FRAME_BYTES = FRAMES * 2 * 2
        private const val MIC_FRAME_BYTES = FRAMES * 2
        private const val DRAIN_TIMEOUT_NS = 3_000_000_000L

        /**
         * Game sound (stereo) plus mic (mono) summed with clamping.
         * Internal so the JVM fixture can drive it with synthetic PCM;
         * that verifies mix math only, never real microphone signal.
         */
        internal fun mixPcm(game: ShortArray?, mic: ShortArray?, out: ShortArray) {
            for (i in 0 until FRAMES) {
                val gl = game?.get(i * 2)?.toInt() ?: 0
                val gr = game?.get(i * 2 + 1)?.toInt() ?: 0
                val m = mic?.get(i)?.toInt() ?: 0
                out[i * 2] = clampPcm(gl + m)
                out[i * 2 + 1] = clampPcm(gr + m)
            }
        }

        private fun clampPcm(value: Int): Short = when {
            value > Short.MAX_VALUE -> Short.MAX_VALUE
            value < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> value.toShort()
        }
    }
}
