package dev.tapio.hush.core.audio

import dev.tapio.hush.core.audio.testing.RenderHarness
import dev.tapio.hush.core.audio.testing.SignalAnalysis
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Performance sanity check.
 *
 * The product requirement is that a three-layer mix stays well under 5 % of one
 * core on a mid-range phone. That cannot be measured here, so this test
 * measures the proxy the JVM can measure: how long it takes to render 60 s of
 * the *most expensive* three-layer combination the catalog allows
 * (thunderstorm + ocean + campfire — two voice pools, three brown sources,
 * a dozen biquads and a 0.4 s delay line).
 *
 * The threshold is deliberately generous (a shared CI machine is nothing like
 * a quiet desktop) — the number it prints is the useful part, and a regression
 * that mattered would blow past 15 s rather than creep toward it. On this
 * project's dev machine the figure is comfortably under 3 s, i.e. the render
 * runs more than 20x faster than real time on one JVM thread.
 *
 * ### Why there are no allocations in the hot loop
 * This is verified by inspection, and the design makes it checkable:
 *  * `MixRenderer` builds every generator, scratch buffer and voice pool in its
 *    constructor; `render` only reads an `AtomicReference` and indexes arrays.
 *  * Generators keep state in fields and primitive arrays; none of them
 *    constructs an object, boxes a primitive, or iterates a `List` per sample
 *    (`Mix.layers` is only indexed when a snapshot changes, and `List.get` on
 *    an `ArrayList` allocates nothing).
 *  * Voice pools are struct-of-arrays with a free stack, so starting and
 *    ending an event moves ints, never allocates.
 *  * The only lambdas in the engine are the sleep-fade callback (created by
 *    the caller, off the audio thread) and `require`'s message blocks, which
 *    are `inline`.
 * A steady-state render therefore produces no garbage, which is what keeps a
 * GC pause from turning into an audible dropout at 3 a.m.
 */
class RenderBenchmarkTest {

    @Test
    @DisplayName("60 s of the heaviest three-layer mix renders far faster than real time")
    fun heavyMixIsFastEnough() {
        val config = EngineConfig(sampleRate = SAMPLE_RATE, blockFrames = BLOCK)
        val mix = Mix.of(
            SoundId.THUNDERSTORM to 0.8f,
            SoundId.OCEAN to 0.8f,
            SoundId.CAMPFIRE to 0.8f,
        )

        // Warm up the JIT on a short render first; timing cold interpreted
        // bytecode would measure the JVM, not the DSP.
        val warmUp = MixRenderer(config, RenderHarness.SEED)
        warmUp.setMix(mix)
        warmUp.start()
        RenderHarness.advance(warmUp, 10.0, SAMPLE_RATE)

        val renderer = MixRenderer(config, RenderHarness.SEED)
        renderer.setMix(mix)
        renderer.setMasterVolume(1f)
        renderer.start()

        val left = FloatArray(BLOCK)
        val right = FloatArray(BLOCK)
        val blocks = (SECONDS * SAMPLE_RATE / BLOCK).toInt()

        val startedAt = System.nanoTime()
        repeat(blocks) { renderer.render(left, right, BLOCK) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0

        val realTimeFactor = SECONDS * 1_000.0 / elapsedMs
        println(
            "rendered %.0f s of thunderstorm+ocean+campfire in %.0f ms (%.1fx real time)".format(
                SECONDS, elapsedMs, realTimeFactor,
            ),
        )

        assertTrue(SignalAnalysis.allFinite(left), "the benchmark mix produced non-finite output")
        assertTrue(
            elapsedMs < BUDGET_MS,
            "60 s of a three-layer mix took ${elapsedMs.toLong()} ms, budget ${BUDGET_MS.toLong()} ms",
        )
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 2048
        const val SECONDS = 60.0
        const val BUDGET_MS = 15_000.0
    }
}
