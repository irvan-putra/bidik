package com.vanipy.bidik

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic mixer fixture: known PCM on both inputs must both survive
 * in the single output track. This verifies the mix math only; it is
 * not evidence that a real microphone delivers signal - genuine mic
 * integration needs its own check on hardware that has one.
 */
class AppAudioMixTest {

    @Test
    fun gameOnlyPassesThrough() {
        val game = ShortArray(2_048) { (it % 500 - 250).toShort() }
        val out = ShortArray(2_048)
        AppAudioRecorder.mixPcm(game, null, out)
        for (i in game.indices) {
            assertEquals(game[i], out[i])
        }
    }

    @Test
    fun micOnlyDuplicatesToBothChannels() {
        val mic = ShortArray(1_024) { 1_000.toShort() }
        val out = ShortArray(2_048)
        AppAudioRecorder.mixPcm(null, mic, out)
        for (i in 0 until 1_024) {
            assertEquals(1_000, out[i * 2].toInt())
            assertEquals(1_000, out[i * 2 + 1].toInt())
        }
    }

    @Test
    fun bothSourcesSurviveInTheMix() {
        // Distinctive levels: game 4000, mic 9000, sum 13000 - if
        // either source were dropped the sum would not land.
        val game = ShortArray(2_048) { 4_000.toShort() }
        val mic = ShortArray(1_024) { 9_000.toShort() }
        val out = ShortArray(2_048)
        AppAudioRecorder.mixPcm(game, mic, out)
        for (i in out.indices) {
            assertEquals(13_000, out[i].toInt())
        }
    }

    @Test
    fun sumsClampInsteadOfWrapping() {
        val game = ShortArray(2_048) { 30_000.toShort() }
        val mic = ShortArray(1_024) { 20_000.toShort() }
        val out = ShortArray(2_048)
        AppAudioRecorder.mixPcm(game, mic, out)
        for (i in out.indices) {
            assertEquals(Short.MAX_VALUE.toInt(), out[i].toInt())
        }
        val gameNeg = ShortArray(2_048) { (-30_000).toShort() }
        val micNeg = ShortArray(1_024) { (-20_000).toShort() }
        AppAudioRecorder.mixPcm(gameNeg, micNeg, out)
        for (i in out.indices) {
            assertEquals(Short.MIN_VALUE.toInt(), out[i].toInt())
        }
    }
}
