package com.vanipy.bidik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ShakeModel: the accelerometer-to-picture-transform math behind the
 * shake recording option. Pure JVM tests; the rendered displacement is
 * proven on the emulator in ScreenRecordTest.shakeEffectDisplacesVideo.
 */
class ShakeModelTest {

    private fun feed(model: ShakeModel, n: Int, x: Float, y: Float, z: Float) {
        repeat(n) { model.onSample(x, y, z) }
    }

    private fun frames(model: ShakeModel, n: Int) {
        repeat(n) { model.advanceFrame() }
    }

    @Test
    fun restingPhoneKeepsPictureStill() {
        val model = ShakeModel()
        feed(model, 300, 0f, 9.81f, 0f)
        frames(model, 90)
        assertEquals(0f, model.offsetX, 0.004f)
        assertEquals(0f, model.offsetY, 0.004f)
        assertEquals(0f, model.rotationDeg, 0.2f)
        assertTrue("rest scale ${model.scale}", model.scale < 1.01f)
    }

    @Test
    fun sustainedTiltDisplacesWithinCaps() {
        val model = ShakeModel()
        feed(model, 200, 0f, 9.81f, 0f)
        feed(model, 100, 4.9f, 8.5f, 0f) // ~30 degrees, held ~2s at 50Hz
        frames(model, 60)
        assertTrue(
            "held tilt did not move the picture: ${model.offsetX}",
            model.offsetX > 0.015f
        )
        assertTrue(model.offsetX <= ShakeModel.MAX_SHIFT + 1e-4f)
        assertTrue(
            "roll cap exceeded: ${model.rotationDeg}",
            kotlin.math.abs(model.rotationDeg) <= ShakeModel.MAX_ROT_DEG + 1e-3f
        )
        assertCoverHolds(model)
    }

    @Test
    fun hardShakeClampsThenSpringsBack() {
        val model = ShakeModel()
        feed(model, 200, 0f, 9.81f, 0f)
        var peak = 0f
        repeat(60) {
            feed(model, 5, 14f, 4f, 6f)
            feed(model, 5, -14f, 16f, -4f)
            model.advanceFrame()
            assertTrue(model.offsetX <= ShakeModel.MAX_SHIFT + 1e-4f)
            assertTrue(model.offsetX >= -ShakeModel.MAX_SHIFT - 1e-4f)
            assertTrue(model.offsetY <= ShakeModel.MAX_SHIFT + 1e-4f)
            assertTrue(model.offsetY >= -ShakeModel.MAX_SHIFT - 1e-4f)
            assertTrue(kotlin.math.abs(model.rotationDeg) <= ShakeModel.MAX_ROT_DEG + 1e-3f)
            peak = maxOf(
                peak,
                kotlin.math.abs(model.offsetX),
                kotlin.math.abs(model.offsetY)
            )
            assertCoverHolds(model)
        }
        assertTrue("hard shake never became visible (peak $peak)", peak > 0.03f)

        feed(model, 400, 0f, 9.81f, 0f)
        frames(model, 120)
        assertEquals(0f, model.offsetX, 0.005f)
        assertEquals(0f, model.offsetY, 0.005f)
        assertEquals(0f, model.rotationDeg, 0.3f)
    }

    @Test
    fun coverScaleHidesEdgesAcrossRandomMotion() {
        val model = ShakeModel()
        val rnd = kotlin.random.Random(42)
        repeat(500) {
            model.onSample(
                rnd.nextFloat() * 24f - 12f,
                rnd.nextFloat() * 24f - 2f,
                rnd.nextFloat() * 16f - 8f
            )
            model.advanceFrame()
            assertCoverHolds(model)
        }
    }

    @Test
    fun extremeSamplesStayFinite() {
        val model = ShakeModel()
        feed(model, 100, 1e6f, -1e6f, 1e6f)
        frames(model, 10)
        assertTrue(model.offsetX.isFinite())
        assertTrue(model.offsetY.isFinite())
        assertTrue(model.rotationDeg.isFinite())
        assertTrue(model.scale.isFinite())
    }

    /**
     * The drawn quad (rotated by rotationDeg, scaled by scale, shifted
     * by the offsets) must still cover the whole viewport: no black
     * edges may ever peek through.
     */
    private fun assertCoverHolds(model: ShakeModel) {
        val rad = Math.toRadians(model.rotationDeg.toDouble())
        val need = 1.0 + maxOf(
            kotlin.math.abs(model.offsetX),
            kotlin.math.abs(model.offsetY)
        )
        val have = model.scale * (kotlin.math.cos(rad) + kotlin.math.sin(rad))
        assertTrue("cover gap: need $need have $have", have >= need - 1e-3)
    }
}
