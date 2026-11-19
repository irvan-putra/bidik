package com.vanipy.bidik

import java.io.OutputStream

/**
 * Minimal GIF89a writer with a fixed 216-colour palette and LZW
 * compression. Pure Kotlin/JVM (no Android types) so the encoder is
 * unit-tested on the build host with the JDK's own GIF reader.
 *
 * Format choices, deliberately simple:
 * - one global colour table: the 6x6x6 uniform "web safe" cube (216
 *   entries) padded to 256. A uniform cube needs no per-image palette
 *   pass and maps every RGB triple to an index with three divisions,
 *   which keeps export fast on a phone. Floyd-Steinberg dithering in
 *   [mapToPalette] hides most of the banding this would otherwise show.
 * - every frame is a full-size image descriptor (no delta rectangles),
 *   disposal method 1 (leave in place): decoders then all agree on what
 *   the animation shows.
 * - looping uses the NETSCAPE2.0 application extension with count 0,
 *   the conventional "loop forever" marking.
 *
 * "Perfect loop" is not something an encoder can promise: the GIF
 * repeats seamlessly only when its last frame leads back into its
 * first. Finding such a pair of frames is LoopFinder's job; this class
 * only writes what it is given.
 */
object GifEncoder {

    private const val PALETTE_SIZE = 256
    private const val MIN_CODE_SIZE = 8
    private const val CLEAR_CODE = 1 shl MIN_CODE_SIZE // 256
    private const val EOI_CODE = CLEAR_CODE + 1 // 257
    private const val MAX_DICT_SIZE = 4096

    /** The 6x6x6 cube; index = r6 * 36 + g6 * 6 + b6, channel steps of 51. */
    internal val palette: IntArray = IntArray(PALETTE_SIZE).also { table ->
        var i = 0
        for (r in 0..5) for (g in 0..5) for (b in 0..5) {
            table[i++] = (r * 51 shl 16) or (g * 51 shl 8) or (b * 51)
        }
        // Remaining entries stay black; the mapper never emits them.
    }

    /** Nearest palette index for an RGB triple by rounding to the cube. */
    internal fun nearestIndex(r: Int, g: Int, b: Int): Int {
        val r6 = (r.coerceIn(0, 255) * 5 + 127) / 255
        val g6 = (g.coerceIn(0, 255) * 5 + 127) / 255
        val b6 = (b.coerceIn(0, 255) * 5 + 127) / 255
        return r6 * 36 + g6 * 6 + b6
    }

    /**
     * Maps ARGB pixels to palette indices. With [dither], quantisation
     * error is diffused to the right and downward neighbours
     * (Floyd-Steinberg), so smooth areas do not band.
     */
    fun mapToPalette(pixels: IntArray, width: Int, height: Int, dither: Boolean = true): ByteArray {
        require(pixels.size == width * height) { "pixel count does not match dimensions" }
        val out = ByteArray(pixels.size)
        if (!dither) {
            for (i in pixels.indices) {
                val p = pixels[i]
                out[i] = nearestIndex(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF).toByte()
            }
            return out
        }
        // Work in widened channels so error diffusion can go negative.
        val wr = IntArray(pixels.size)
        val wg = IntArray(pixels.size)
        val wb = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            wr[i] = p shr 16 and 0xFF
            wg[i] = p shr 8 and 0xFF
            wb[i] = p and 0xFF
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val idx = nearestIndex(wr[i], wg[i], wb[i])
                out[i] = idx.toByte()
                val c = palette[idx]
                val er = wr[i] - (c shr 16 and 0xFF)
                val eg = wg[i] - (c shr 8 and 0xFF)
                val eb = wb[i] - (c and 0xFF)
                fun spread(j: Int, sixteenth: Int) {
                    if (j < 0 || j >= pixels.size) return
                    wr[j] += er * sixteenth / 16
                    wg[j] += eg * sixteenth / 16
                    wb[j] += eb * sixteenth / 16
                }
                if (x + 1 < width) spread(i + 1, 7)
                if (y + 1 < height) {
                    if (x > 0) spread(i + width - 1, 3)
                    spread(i + width, 5)
                    if (x + 1 < width) spread(i + width + 1, 1)
                }
            }
        }
        return out
    }

    /**
     * Writes a complete GIF stream. [frames] are palette-index buffers
     * of width*height bytes each (see [mapToPalette]); [delayCs] is the
     * per-frame delay in centiseconds; [loop] adds the infinite-loop
     * extension.
     */
    fun write(
        out: OutputStream,
        width: Int,
        height: Int,
        frames: List<ByteArray>,
        delayCs: Int,
        loop: Boolean = true
    ) {
        require(width > 0 && height > 0)
        require(frames.isNotEmpty()) { "a GIF needs at least one frame" }
        frames.forEach { require(it.size == width * height) { "frame size mismatch" } }
        require(delayCs in 1..65535)

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical screen descriptor: global table of 256 entries, 8-bit.
        out.write(width and 0xFF)
        out.write(width shr 8 and 0xFF)
        out.write(height and 0xFF)
        out.write(height shr 8 and 0xFF)
        out.write(0xF7)
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        for (c in palette) {
            out.write(c shr 16 and 0xFF)
            out.write(c shr 8 and 0xFF)
            out.write(c and 0xFF)
        }
        if (loop) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(3, 1, 0, 0, 0)) // sub-block: id 1, count 0 = forever
        }
        for (frame in frames) {
            // Graphic control extension: disposal 1 (leave), no transparency.
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))
            out.write(delayCs and 0xFF)
            out.write(delayCs shr 8 and 0xFF)
            out.write(0) // transparent colour index (unused)
            out.write(0) // block terminator
            // Image descriptor, full frame at origin, no local table.
            out.write(0x2C)
            out.write(byteArrayOf(0, 0, 0, 0))
            out.write(width and 0xFF)
            out.write(width shr 8 and 0xFF)
            out.write(height and 0xFF)
            out.write(height shr 8 and 0xFF)
            out.write(0)
            // Compressed image data.
            out.write(MIN_CODE_SIZE)
            writeLzw(out, frame)
            out.write(0) // block terminator
        }
        out.write(0x3B)
    }

    /**
     * GIF-variant LZW: codes packed LSB-first into sub-blocks of at most
     * 255 bytes, dictionary reset with a clear code whenever it fills.
     */
    private fun writeLzw(out: OutputStream, indices: ByteArray) {
        var dict = HashMap<Long, Int>()
        var nextCode = EOI_CODE + 1
        var codeSize = MIN_CODE_SIZE + 1
        val block = ByteArray(255)
        var blockLen = 0
        var bitBuffer = 0
        var bitCount = 0

        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                block[blockLen++] = (bitBuffer and 0xFF).toByte()
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
                if (blockLen == block.size) {
                    out.write(blockLen)
                    out.write(block, 0, blockLen)
                    blockLen = 0
                }
            }
        }

        fun resetDict() {
            dict = HashMap()
            nextCode = EOI_CODE + 1
            codeSize = MIN_CODE_SIZE + 1
        }

        emit(CLEAR_CODE)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix.toLong() shl 8) or k.toLong()
            val existing = dict[key]
            if (existing != null) {
                prefix = existing
            } else {
                emit(prefix)
                dict[key] = nextCode++
                // Grow one entry after the dictionary fills the current
                // width: the decoder adds its entries one code behind
                // the encoder, so the code that makes the decoder grow
                // is still emitted at the old width.
                if (nextCode == (1 shl codeSize) + 1 && codeSize < 12) {
                    codeSize++
                }
                if (nextCode >= MAX_DICT_SIZE) {
                    emit(CLEAR_CODE)
                    resetDict()
                }
                prefix = k
            }
        }
        emit(prefix)
        emit(EOI_CODE)
        if (bitCount > 0) {
            block[blockLen++] = (bitBuffer and 0xFF).toByte()
        }
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
        }
    }
}
