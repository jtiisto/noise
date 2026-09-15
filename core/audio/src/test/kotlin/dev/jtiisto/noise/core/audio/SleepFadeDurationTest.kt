package dev.jtiisto.noise.core.audio

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

private object FlatFactory : GeneratorFactory {
    override fun create(id: SoundId, sampleRate: Int, seed: Long): SoundGenerator =
        object : SoundGenerator {
            override fun render(left: FloatArray, right: FloatArray, frames: Int) {
                left.fill(0.5f, 0, frames)
                right.fill(0.5f, 0, frames)
            }

            override fun reset(seed: Long) = Unit
        }
}

/**
 * The sleep fade has to last exactly as long as the user asked for.
 *
 * Engine review #4 (ported from Notch): driving the envelope with a Float step
 * of `phase / durationSamples`, floored at a `MIN_STEP` constant and
 * accumulated one subtraction per sample, made the duration a function of the
 * sample rate — a requested 120 s finished in 117 s at 48 kHz and in 49 s at
 * 192 kHz. The envelope now counts samples, so this suite pins the duration at
 * every rate Android realistically reports and at both ends of the option list.
 */
class SleepFadeDurationTest {

    @Test
    @DisplayName("a sleep fade completes within 0.1 % of its requested duration at every rate")
    fun fadeLastsTheRequestedTime() {
        for (rate in RATES) {
            for (seconds in DURATIONS) {
                val expected = seconds.toLong() * rate
                val measured = startedRenderer(rate).fadeFrames(rate, seconds * MILLIS_PER_SECOND)
                val errorPercent = abs(measured - expected) * 100.0 / expected
                assertTrue(
                    errorPercent <= 0.1,
                    "a %d s fade at %d Hz finished after %.2f s (%.2f %% out)".format(
                        seconds,
                        rate,
                        measured.toDouble() / rate,
                        errorPercent,
                    ),
                )
            }
        }
    }

    @Test
    @DisplayName("a fade re-armed part-way through still takes the new duration in full")
    fun reArmedFadeTakesItsFullDuration() {
        val rate = 48_000
        val renderer = startedRenderer(rate)
        // Half of a 10 s fade, then a 4 s one from wherever the envelope is.
        renderer.beginFadeOut(10_000) { }
        renderer.advanceBy(5 * rate)

        val measured = renderer.fadeFrames(rate, 4 * MILLIS_PER_SECOND)

        val expected = 4L * rate
        val errorPercent = abs(measured - expected) * 100.0 / expected
        assertTrue(
            errorPercent <= 0.1,
            "the re-armed fade took %.2f s".format(measured.toDouble() / rate),
        )
    }

    // ---- Harness ---------------------------------------------------------------

    private fun config(sampleRate: Int) = EngineConfig(
        sampleRate = sampleRate,
        // The smallest legal block, so "how many samples until the callback"
        // is measured to within 64 frames — two orders of magnitude finer than
        // the 0.1 % the assertions allow.
        blockFrames = BLOCK,
        // A short fade-in keeps settling cheap at 192 kHz. The sleep fade is
        // what is under test, and it starts from a settled envelope either way.
        fadeInMs = 10,
    )

    /** Playing a flat 0.5, start fade already landed. */
    private fun startedRenderer(sampleRate: Int): MixRenderer {
        val renderer = MixRenderer(config(sampleRate), SEED, FlatFactory)
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        renderer.advanceBy(sampleRate / 10)
        return renderer
    }

    private fun MixRenderer.advanceBy(frames: Int) {
        val left = FloatArray(BLOCK)
        val right = FloatArray(BLOCK)
        var remaining = frames
        while (remaining > 0) {
            val n = minOf(BLOCK, remaining)
            render(left, right, n)
            remaining -= n
        }
    }

    /**
     * Arms a fade and renders until it calls back, returning how many frames
     * that took. The count is rounded up to the block the completion landed
     * in, which is why the blocks are [BLOCK] frames long.
     */
    private fun MixRenderer.fadeFrames(sampleRate: Int, durationMs: Long): Long {
        var completed = false
        beginFadeOut(durationMs) { completed = true }
        val left = FloatArray(BLOCK)
        val right = FloatArray(BLOCK)
        var frames = 0L
        val limit = LIMIT_MULTIPLIER * sampleRate * DURATIONS.max()
        while (!completed && frames < limit) {
            render(left, right, BLOCK)
            frames += BLOCK
        }
        check(completed) { "the fade never completed within $limit frames" }
        return frames
    }

    private companion object {
        const val BLOCK = 64
        const val SEED = 0xBEEFL
        const val MILLIS_PER_SECOND = 1_000L
        const val LIMIT_MULTIPLIER = 2L

        /** Every output rate Android realistically reports, plus the two extremes. */
        val RATES = listOf(44_100, 48_000, 96_000, 192_000)

        /** The shortest and longest sleep-fade options. */
        val DURATIONS = listOf(15, 120)
    }
}
