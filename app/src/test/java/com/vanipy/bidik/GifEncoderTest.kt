package com.vanipy.bidik

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side verification of the GIF writer. Decoding uses a small,
 * independent GIF/LZW reader written for this test (the JDK's ImageIO
 * is not on the Android unit-test classpath); the on-device decode with
 * Android's own GIF decoder is covered by the instrumented test
 * (ScreenRecordTest.loopFoundAndGifExported).
 */
class GifEncoderTest {

    private fun twoTonePixels(width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            if (i % width < width / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        }

    private fun encode(width: Int, height: Int, frames: List<ByteArray>, delayCs: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        GifEncoder.write(out, width, height, frames, delayCs, loop = true)
        return out.toByteArray()
    }

    /** Minimal GIF89a reader: header, global table, loop ext, frames. */
    private class Reader(val bytes: ByteArray) {
        var pos = 0
        val frames = mutableListOf<ByteArray>()
        var width = 0
        var height = 0
        var loopsForever = false

        private fun u8() = bytes[pos++].toInt() and 0xFF
        private fun u16() = u8() or (u8() shl 8)

        fun parse() {
            assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
            pos = 6
            width = u16()
            height = u16()
            val packed = u8()
            u8(); u8()
            if (packed and 0x80 != 0) {
                pos += 3 * (1 shl ((packed and 0x07) + 1))
            }
            while (true) {
                when (val block = u8()) {
                    0x21 -> { // extension
                        val label = u8()
                        if (label == 0xFF) {
                            val len = u8()
                            val name = String(bytes, pos, len, Charsets.US_ASCII)
                            pos += len
                            while (true) {
                                val n = u8()
                                if (n == 0) break
                                if (name == "NETSCAPE2.0" && n == 3) {
                                    val id = u8()
                                    val count = u16()
                                    if (id == 1 && count == 0) loopsForever = true
                                } else {
                                    pos += n
                                }
                            }
                        } else {
                            skipSubBlocks()
                        }
                    }
                    0x2C -> { // image descriptor
                        u16(); u16()
                        val w = u16()
                        val h = u16()
                        val ipacked = u8()
                        if (ipacked and 0x80 != 0) {
                            pos += 3 * (1 shl ((ipacked and 0x07) + 1))
                        }
                        val minCode = u8()
                        val data = ByteArrayOutputStream()
                        while (true) {
                            val n = u8()
                            if (n == 0) break
                            data.write(bytes, pos, n)
                            pos += n
                        }
                        frames.add(lzwDecode(data.toByteArray(), minCode, w * h))
                    }
                    0x3B -> return
                    else -> error("unknown block $block at $pos")
                }
            }
        }

        private fun skipSubBlocks() {
            while (true) {
                val n = u8()
                if (n == 0) return
                pos += n
            }
        }

        private fun lzwDecode(data: ByteArray, minCodeSize: Int, pixels: Int): ByteArray {
            val clear = 1 shl minCodeSize
            val eoi = clear + 1
            var dict = (0 until clear).map { intArrayOf(it) }.toMutableList()
            dict.add(intArrayOf()) // clear
            dict.add(intArrayOf()) // eoi
            var codeSize = minCodeSize + 1
            var bitPos = 0
            val out = ByteArrayOutputStream(pixels)
            var prev: IntArray? = null
            fun readCode(): Int {
                var code = 0
                for (b in 0 until codeSize) {
                    val byteIndex = bitPos / 8
                    if (byteIndex >= data.size) return -1
                    val bit = (data[byteIndex].toInt() shr (bitPos % 8)) and 1
                    code = code or (bit shl b)
                    bitPos++
                }
                return code
            }
            while (true) {
                val code = readCode()
                if (code < 0 || code == eoi) break
                if (code == clear) {
                    dict = (0 until clear).map { intArrayOf(it) }.toMutableList()
                    dict.add(intArrayOf())
                    dict.add(intArrayOf())
                    codeSize = minCodeSize + 1
                    prev = null
                    continue
                }
                val entry: IntArray = when {
                    code < dict.size -> dict[code]
                    code == dict.size && prev != null -> prev!! + prev!![0]
                    else -> error("bad code $code")
                }
                out.write(ByteArray(entry.size) { entry[it].toByte() }, 0, entry.size)
                if (prev != null) {
                    dict.add(prev!! + entry[0])
                    if (dict.size == (1 shl codeSize) && codeSize < 12) codeSize++
                }
                prev = entry
            }
            return out.toByteArray()
        }
    }

    @Test
    fun writesReadableLoopingGif() {
        val pixels = twoTonePixels(8, 8)
        val indices = GifEncoder.mapToPalette(pixels, 8, 8, dither = false)
        val bytes = encode(8, 8, listOf(indices, indices))

        val reader = Reader(bytes)
        reader.parse()
        assertEquals(8, reader.width)
        assertEquals(8, reader.height)
        assertTrue("infinite-loop marker missing", reader.loopsForever)
        assertEquals(2, reader.frames.size)
        assertTrue("decoded frame differs from the encoded indices",
            reader.frames[0].contentEquals(indices))
    }

    @Test
    fun lzwRoundTripsNoisyFramesPastDictionaryReset() {
        // 3 frames of pseudo-noise force dictionary growth to the 4096
        // cap and several clear-code resets; the decoded indices must
        // still match the source exactly.
        val rnd = java.util.Random(42)
        val pixels = IntArray(64 * 64) { rnd.nextInt() }
        val indices = GifEncoder.mapToPalette(pixels, 64, 64, dither = false)
        val bytes = encode(64, 64, listOf(indices, indices, indices))

        val reader = Reader(bytes)
        reader.parse()
        assertEquals(3, reader.frames.size)
        assertTrue(reader.frames[2].contentEquals(indices))
    }

    @Test
    fun ditheringPreservesAverageBrightness() {
        // A mid-gray block dithers to a mix of the two nearest greys;
        // the average must stay close to the source, not clamp to one.
        val gray = 0xFF808080.toInt()
        val pixels = IntArray(32 * 32) { gray }
        val indices = GifEncoder.mapToPalette(pixels, 32, 32, dither = true)
        var sum = 0L
        for (b in indices) {
            val c = GifEncoder.palette[b.toInt() and 0xFF]
            sum += (c shr 16 and 0xFF)
        }
        val avg = sum.toDouble() / indices.size
        assertTrue("dithered average $avg drifted from 128", avg in 110.0..146.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyAnimation() {
        encode(8, 8, emptyList())
    }
}
