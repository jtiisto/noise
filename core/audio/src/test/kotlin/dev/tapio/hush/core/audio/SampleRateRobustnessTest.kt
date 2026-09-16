package dev.tapio.hush.core.audio

import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Phones do not all run at 48 kHz: the engine builds its renderer at the
 * device's native output rate (44.1 kHz is common, 96 kHz exists). Every
 * generator has to be constructible and stable at any of them — a filter
 * tuned above Nyquist or a table sized from the rate must not throw or blow
 * up, because an exception on the audio thread silences the app and a
 * crash on the main thread (engine.setMix) kills it.
 */
class SampleRateRobustnessTest {

    @ParameterizedTest
    @ValueSource(ints = [22_050, 44_100, 48_000, 88_200, 96_000, 192_000])
    fun `every generator renders finite audio at any sample rate`(sampleRate: Int) {
        val frames = 1024
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (id in SoundId.entries) {
            val generator = Generators.create(id, sampleRate, seed = 42L)
            repeat((sampleRate * 3) / frames) { generator.render(left, right, frames) }
            for (i in 0 until frames) {
                assertTrue(left[i].isFinite() && right[i].isFinite(), "$id produced non-finite output at $sampleRate Hz")
                assertTrue(kotlin.math.abs(left[i]) <= 1.5f, "$id sample out of range at $sampleRate Hz: ${left[i]}")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [44_100, 48_000, 96_000])
    fun `a full mix can be built and changed while rendering at any rate`(sampleRate: Int) {
        val config = EngineConfig(sampleRate = sampleRate)
        val renderer = MixRenderer(config)
        val left = FloatArray(config.blockFrames)
        val right = FloatArray(config.blockFrames)
        renderer.start()
        renderer.setMix(Mix.of(SoundId.RAIN to 0.7f))
        repeat(20) { renderer.render(left, right, config.blockFrames) }
        renderer.setMix(Mix.of(SoundId.RAIN to 0.7f, SoundId.BROWN to 0.7f, SoundId.AIRPLANE to 0.7f))
        repeat(40) { renderer.render(left, right, config.blockFrames) }
        assertFalse(left.any { !it.isFinite() } || right.any { !it.isFinite() }, "non-finite output at $sampleRate Hz")
        assertTrue(renderer.activeLayerCount == 3)
    }
}
