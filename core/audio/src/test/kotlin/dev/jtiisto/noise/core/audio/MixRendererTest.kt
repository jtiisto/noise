package dev.jtiisto.noise.core.audio

import dev.jtiisto.noise.core.audio.dsp.SoftClipper
import dev.jtiisto.noise.core.audio.testing.RenderHarness
import dev.jtiisto.noise.core.audio.testing.SignalAnalysis
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * A generator whose output is a known constant, so mixing behaviour can be
 * asserted on exactly.
 *
 * Every one of these tests would be untestable against real noise: a
 * "sample-to-sample delta below 0.05" assertion is meaningless when the source
 * itself jumps by 0.5 between samples. With a DC source, any delta in the
 * output *is* a gain step, which is precisely the thing that must never happen.
 */
private class ConstantGenerator(private val level: Float) : SoundGenerator {
    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = level
            right[i] = level
        }
    }

    override fun reset(seed: Long) = Unit
}

private class ConstantFactory(private val level: Float = 0.5f) : GeneratorFactory {
    override fun create(id: SoundId, sampleRate: Int, seed: Long): SoundGenerator =
        ConstantGenerator(level)
}

/**
 * Deliberately unequal per-sound levels so that swapping the mix really does
 * change the output level; with equal levels a broken crossfade would produce
 * the right constant by accident and the continuity assertion would be vacuous.
 */
private class PerSoundFactory : GeneratorFactory {
    override fun create(id: SoundId, sampleRate: Int, seed: Long): SoundGenerator =
        ConstantGenerator(
            when (id) {
                SoundId.WHITE -> 0.6f
                SoundId.RAIN -> 0.25f
                else -> 0.1f
            },
        )
}

class MixRendererTest {

    private val config = EngineConfig(sampleRate = SAMPLE_RATE, blockFrames = BLOCK)

    // ---- Gain mapping ---------------------------------------------------------

    @Test
    @DisplayName("the slider maps to amplitude as slider squared")
    fun sliderMapsQuadratically() {
        assertEquals(0f, MixRenderer.layerGain(0f))
        assertEquals(0.25f, MixRenderer.layerGain(0.5f))
        assertEquals(1f, MixRenderer.layerGain(1f))
        // Out-of-range input is clamped rather than producing a negative gain.
        assertEquals(0f, MixRenderer.layerGain(-1f))
        assertEquals(1f, MixRenderer.layerGain(2f))
    }

    @Test
    @DisplayName("a layer at slider 0.5 arrives at a quarter amplitude, through the clipper")
    fun layerGainReachesTheOutput() {
        val renderer = renderer(ConstantFactory(1f))
        renderer.setMix(Mix.of(SoundId.WHITE to 0.5f))
        renderer.start()
        val steady = settle(renderer)
        val expected = SoftClipper.clip(MixRenderer.layerGain(0.5f))
        assertEquals(expected.toDouble(), steady.left.last().toDouble(), 1e-4)
    }

    @Test
    @DisplayName("master volume multiplies the layer gain")
    fun masterVolumeApplies() {
        val renderer = renderer(ConstantFactory(1f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.setMasterVolume(0.5f)
        renderer.start()
        val steady = settle(renderer)
        assertEquals(SoftClipper.clip(0.5f).toDouble(), steady.left.last().toDouble(), 1e-4)
    }

    // ---- Crossfades -----------------------------------------------------------

    @Test
    @DisplayName("adding and removing layers never produces a sample-to-sample jump")
    fun crossfadesAreContinuous() {
        val renderer = renderer(PerSoundFactory())
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        settle(renderer)

        // Swap the whole mix mid-flight: one layer fades out while two fade in.
        renderer.setMix(Mix.of(SoundId.RAIN to 1f, SoundId.OCEAN to 1f))
        val crossfade = RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE)
        val delta = SignalAnalysis.maxAbsDelta(crossfade.left)
        val swing = crossfade.left.max() - crossfade.left.min()
        println("crossfade: max step %.6f over a %.3f swing".format(delta, swing))
        assertTrue(swing > 0.1f, "the test mix did not actually change level; the assertion is vacuous")
        assertTrue(delta <= 0.05, "crossfade stepped by $delta")

        // ...and back again, which exercises reviving a still-fading slot.
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        val back = RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE)
        val backDelta = SignalAnalysis.maxAbsDelta(back.left)
        assertTrue(backDelta <= 0.05, "return crossfade stepped by $backDelta")
    }

    @Test
    @DisplayName("re-adding a sound that is still fading out does not double its level")
    fun revivingAFadingLayerDoesNotDouble() {
        val renderer = renderer(ConstantFactory(0.3f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        settle(renderer)
        renderer.setMix(Mix.EMPTY)
        RenderHarness.renderMix(renderer, 0.1, SAMPLE_RATE) // part-way through the fade
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        val back = settle(renderer)
        assertEquals(SoftClipper.clip(0.3f).toDouble(), back.left.last().toDouble(), 1e-3)
        assertEquals(1, renderer.activeLayerCount)
    }

    @Test
    @DisplayName("a removed layer is dropped once its fade completes")
    fun removedLayersAreReleased() {
        val renderer = renderer(ConstantFactory(0.3f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f, SoundId.PINK to 1f))
        renderer.start()
        settle(renderer)
        assertEquals(2, renderer.activeLayerCount)
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE)
        assertEquals(1, renderer.activeLayerCount)
    }

    @Test
    @DisplayName("volume changes during a crossfade do not stall it")
    fun volumeChangesDoNotRestartCrossfades() {
        val renderer = renderer(ConstantFactory(0.4f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        settle(renderer)
        renderer.setMix(Mix.of(SoundId.PINK to 1f))
        // Hammer the master volume the way a slider drag would.
        val block = FloatArray(BLOCK)
        val blockRight = FloatArray(BLOCK)
        repeat(30) {
            renderer.setMasterVolume(1f)
            renderer.render(block, blockRight, BLOCK)
        }
        assertEquals(1, renderer.activeLayerCount, "the outgoing layer never finished fading")
    }

    // ---- Start / stop fades ---------------------------------------------------

    @Test
    @DisplayName("start fades in from silence and stop fades back to it")
    fun startAndStopFade() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))

        val beforeStart = RenderHarness.renderMix(renderer, 0.1, SAMPLE_RATE)
        assertEquals(0f, SignalAnalysis.peak(beforeStart.left), "output before start() is not silent")

        renderer.start()
        val fadeIn = RenderHarness.renderMix(renderer, 2.0, SAMPLE_RATE)
        assertEquals(0f, fadeIn.left[0], 1e-6f, "fade-in did not begin at exactly zero")
        assertTrue(fadeIn.left.last() > 0.4f, "fade-in did not reach full level")
        assertTrue(SignalAnalysis.maxAbsDelta(fadeIn.left) <= 0.05, "fade-in stepped")
        assertTrue(isMonotonicNonDecreasing(fadeIn.left), "fade-in is not monotonic")

        renderer.stop()
        val fadeOut = RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE)
        assertEquals(0f, fadeOut.left.last(), 1e-6f, "fade-out did not reach exactly zero")
        assertTrue(renderer.isFinished, "renderer did not report finished after the stop fade")
    }

    @Test
    @DisplayName("start and stop are idempotent")
    fun startStopAreIdempotent() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        renderer.start()
        val a = settle(renderer)
        renderer.start()
        val b = settle(renderer)
        assertEquals(a.left.last().toDouble(), b.left.last().toDouble(), 1e-6)
        renderer.stop()
        renderer.stop()
        RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE)
        assertTrue(renderer.isFinished)
    }

    @Test
    @DisplayName("a stop that lands before the first render still reports finished")
    fun stopBeforeTheFirstRenderFinishes() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        // Both commands land inside one block, so the renderer only ever sees
        // the stop. Requiring a previously rendered start left isFinished false
        // forever and the sink wrote silence for the rest of the process's life
        // (engine review #3, ported from Notch 2026-09-15).
        renderer.start()
        renderer.stop()

        val block = FloatArray(BLOCK)
        val blockRight = FloatArray(BLOCK)
        renderer.render(block, blockRight, BLOCK)

        assertTrue(renderer.isFinished, "a coalesced start+stop never finished")
        assertEquals(0f, SignalAnalysis.peak(block))
    }

    // ---- Duck -----------------------------------------------------------------

    @Test
    @DisplayName("ducking ramps down to the configured gain and back up")
    fun duckRamps() {
        val renderer = renderer(ConstantFactory(1f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        settle(renderer)

        renderer.setDucked(true)
        val ducking = RenderHarness.renderMix(renderer, 0.5, SAMPLE_RATE)
        assertTrue(SignalAnalysis.maxAbsDelta(ducking.left) <= 0.05, "duck stepped")
        assertEquals(
            SoftClipper.clip(config.duckGain).toDouble(),
            ducking.left.last().toDouble(),
            1e-3,
        )

        renderer.setDucked(false)
        val restored = RenderHarness.renderMix(renderer, 0.5, SAMPLE_RATE)
        assertTrue(SignalAnalysis.maxAbsDelta(restored.left) <= 0.05, "unduck stepped")
        assertEquals(SoftClipper.clip(1f).toDouble(), restored.left.last().toDouble(), 1e-3)
    }

    // ---- Sleep-timer fade -----------------------------------------------------

    @Test
    @DisplayName("the sleep fade reaches silence and calls back exactly once")
    fun sleepFadeCompletesExactlyOnce() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        settle(renderer)

        val calls = AtomicInteger()
        renderer.beginFadeOut(1_000) { calls.incrementAndGet() }
        val fade = RenderHarness.renderMix(renderer, 1.5, SAMPLE_RATE)
        assertEquals(0f, fade.left.last(), 1e-6f, "sleep fade did not reach exactly zero")
        assertTrue(SignalAnalysis.maxAbsDelta(fade.left) <= 0.05, "sleep fade stepped")
        assertEquals(1, calls.get(), "onComplete was not called exactly once")

        // Keep rendering: it must not fire again.
        RenderHarness.renderMix(renderer, 2.0, SAMPLE_RATE)
        assertEquals(1, calls.get(), "onComplete fired more than once")
        assertTrue(renderer.isFinished)
    }

    @Test
    @DisplayName("cancelling the sleep fade restores gain smoothly and drops the callback")
    fun cancelRestoresGain() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        val full = settle(renderer).left.last()

        val calls = AtomicInteger()
        renderer.beginFadeOut(4_000) { calls.incrementAndGet() }
        val partial = RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE)
        assertTrue(partial.left.last() < full, "the fade never started")

        renderer.cancelFadeOut()
        val restored = RenderHarness.renderMix(renderer, 3.0, SAMPLE_RATE)
        assertTrue(SignalAnalysis.maxAbsDelta(restored.left) <= 0.05, "cancel stepped")
        assertEquals(full.toDouble(), restored.left.last().toDouble(), 1e-4)
        assertEquals(0, calls.get(), "a cancelled fade must never call back")
        assertEquals(false, renderer.isFinished)
    }

    @Test
    @DisplayName("a re-armed sleep fade uses the new duration and only the new callback")
    fun reArmingReplacesTheFade() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        settle(renderer)

        val first = AtomicInteger()
        val second = AtomicInteger()
        renderer.beginFadeOut(10_000) { first.incrementAndGet() }
        RenderHarness.renderMix(renderer, 0.5, SAMPLE_RATE)
        renderer.beginFadeOut(500) { second.incrementAndGet() }
        RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE)

        assertEquals(0, first.get(), "the replaced callback must not fire")
        assertEquals(1, second.get(), "the new callback must fire once")
    }

    @Test
    @DisplayName("a completed sleep fade does not mute the next start")
    fun startAfterACompletedSleepFade() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        settle(renderer)

        val calls = AtomicInteger()
        renderer.beginFadeOut(500) { calls.incrementAndGet() }
        assertEquals(0f, RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE).left.last(), 1e-6f)
        assertEquals(1, calls.get())

        // What the engine does the moment the fade completes, then what the user
        // does next morning. The completed request used to stay in the snapshot
        // with the envelope pinned at zero, so every later start rendered silence
        // for the life of the process (engine review #1, ported from Notch).
        renderer.stop()
        RenderHarness.advance(renderer, 1.0, SAMPLE_RATE)
        renderer.start()

        val restarted = RenderHarness.renderMix(renderer, 2.0, SAMPLE_RATE)
        assertEquals(
            SoftClipper.clip(0.5f).toDouble(),
            restarted.left.last().toDouble(),
            1e-3,
            "the sound never came back after a completed sleep fade",
        )
        assertTrue(SignalAnalysis.maxAbsDelta(restarted.left) <= 0.05, "the restart stepped")
        assertEquals(1, calls.get(), "the completed fade called back again")
    }

    @Test
    @DisplayName("cancelling after the fade completed calls nothing back")
    fun cancelAfterCompletionIsInert() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        settle(renderer)

        val calls = AtomicInteger()
        renderer.beginFadeOut(500) { calls.incrementAndGet() }
        RenderHarness.renderMix(renderer, 1.0, SAMPLE_RATE)
        assertEquals(1, calls.get())

        // A cancel that loses the race with the completion must stay harmless:
        // the callback has already fired and must never fire twice.
        renderer.cancelFadeOut()
        RenderHarness.renderMix(renderer, 3.0, SAMPLE_RATE)
        assertEquals(1, calls.get(), "a late cancel re-opened the completed fade")
    }

    // ---- Clipper --------------------------------------------------------------

    @Test
    @DisplayName("three hot layers are soft-clipped, never wrapped")
    fun clipperBoundsTheSum() {
        val renderer = renderer(ConstantFactory(1f))
        renderer.setMix(
            Mix.of(SoundId.WHITE to 1f, SoundId.PINK to 1f, SoundId.BROWN to 1f),
        )
        renderer.start()
        val steady = settle(renderer)
        val value = steady.left.last()
        assertTrue(value <= 1f, "clipper let $value through")
        // Three unity layers sum to 3.0, which the clipper maps to exactly 1.
        assertEquals(1.0, value.toDouble(), 1e-4)
        assertTrue(SignalAnalysis.allFinite(steady.left))
    }

    @Test
    @DisplayName("the soft clipper is unity at the origin and bounded everywhere")
    fun softClipperShape() {
        assertEquals(0f, SoftClipper.clip(0f))
        assertEquals(0.1f, SoftClipper.clip(0.1f), 0.005f)
        assertEquals(1f, SoftClipper.clip(3f), 1e-6f)
        assertEquals(-1f, SoftClipper.clip(-3f), 1e-6f)
        // Beyond +/-3 the rational function turns back on itself, so the input
        // is clamped first; the output must stay pinned at full scale.
        assertEquals(1f, SoftClipper.clip(50f), 1e-6f)
        assertEquals(-1f, SoftClipper.clip(-50f), 1e-6f)
        var x = -3f
        while (x <= 3f) {
            assertTrue(abs(SoftClipper.clip(x)) <= 1f, "clip($x) escaped full scale")
            x += 0.01f
        }
    }

    // ---- Contract -------------------------------------------------------------

    @Test
    @DisplayName("render rejects blocks larger than the configured block size")
    fun renderRejectsOversizedBlocks() {
        val renderer = renderer(ConstantFactory(0.5f))
        val big = FloatArray(BLOCK * 2)
        assertThrows(IllegalArgumentException::class.java) {
            renderer.render(big, big, BLOCK * 2)
        }
    }

    @Test
    @DisplayName("the renderer reports the rate it was configured with")
    fun exposesSampleRate() {
        assertEquals(SAMPLE_RATE, renderer(ConstantFactory()).sampleRate)
        val at44k = MixRenderer(EngineConfig(sampleRate = 44_100, blockFrames = BLOCK))
        assertEquals(44_100, at44k.sampleRate)
    }

    @Test
    @DisplayName("render rejects output buffers smaller than the frame count")
    fun renderRejectsShortBuffers() {
        val renderer = renderer(ConstantFactory(0.5f))
        val short = FloatArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            renderer.render(short, short, 64)
        }
    }

    @Test
    @DisplayName("a zero-frame render is a no-op")
    fun zeroFramesIsANoOp() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.setMix(Mix.of(SoundId.WHITE to 1f))
        renderer.start()
        val buffer = FloatArray(BLOCK)
        renderer.render(buffer, buffer, 0)
        assertEquals(0f, SignalAnalysis.peak(buffer))
    }

    @Test
    @DisplayName("an empty mix renders digital silence")
    fun emptyMixIsSilent() {
        val renderer = renderer(ConstantFactory(0.5f))
        renderer.start()
        val output = RenderHarness.renderMix(renderer, 0.5, SAMPLE_RATE)
        assertEquals(0f, SignalAnalysis.peak(output.left))
        assertEquals(0f, SignalAnalysis.peak(output.right))
    }

    @Test
    @DisplayName("the real generator set drives the mix without NaNs or clipping artefacts")
    fun realGeneratorsMixCleanly() {
        val renderer = RenderHarness.startedRenderer(
            Mix.of(SoundId.RAIN to 0.7f, SoundId.BROWN to 0.7f, SoundId.CRICKETS to 0.5f),
            config,
        )
        val output = RenderHarness.renderMix(renderer, 5.0, SAMPLE_RATE)
        assertTrue(SignalAnalysis.allFinite(output.left))
        assertTrue(SignalAnalysis.allFinite(output.right))
        assertTrue(SignalAnalysis.peak(output.left) <= 1f)
        assertNotEquals(0f, SignalAnalysis.peak(output.left))
    }

    @Test
    @DisplayName("more layers than slots are handled by stealing, not by crashing")
    fun rapidMixChurnIsSurvivable() {
        val renderer = renderer(ConstantFactory(0.3f))
        renderer.start()
        val ids = SoundId.entries
        val block = FloatArray(BLOCK)
        val blockRight = FloatArray(BLOCK)
        for (i in ids.indices) {
            renderer.setMix(Mix.of(ids[i] to 1f, ids[(i + 1) % ids.size] to 1f))
            renderer.render(block, blockRight, BLOCK)
            assertTrue(SignalAnalysis.allFinite(block))
            assertTrue(SignalAnalysis.peak(block) <= 1f)
        }
        assertTrue(renderer.activeLayerCount <= Mix.MAX_LAYERS * 2)
    }

    private fun renderer(factory: GeneratorFactory) = MixRenderer(config, SEED, factory)

    /** Runs long enough for every ramp and the start fade to settle. */
    private fun settle(renderer: MixRenderer) =
        RenderHarness.renderMix(renderer, 3.0, SAMPLE_RATE)

    private fun isMonotonicNonDecreasing(x: FloatArray): Boolean {
        for (i in 1 until x.size) if (x[i] < x[i - 1] - 1e-6f) return false
        return true
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 2048
        const val SEED = 0xC0FFEEL
    }
}
