package dev.jtiisto.noise.core.audio.testing

import dev.jtiisto.noise.core.audio.EngineConfig
import dev.jtiisto.noise.core.audio.Generators
import dev.jtiisto.noise.core.audio.MixRenderer
import dev.jtiisto.noise.core.audio.SoundGenerator
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId

/** Stereo capture of a render, so tests can talk about `capture.left` rather than two arrays. */
class Capture(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
    val frames: Int get() = left.size
}

/**
 * Offline rendering helpers shared by the generator, calibration, spectrum and
 * WAV-export tests. Everything here is deterministic: fixed seeds, fixed block
 * size, no wall-clock.
 */
object RenderHarness {

    const val SAMPLE_RATE = 48_000
    const val BLOCK = 2048
    const val SEED = 0x1234_5678_9ABC_DEF0L

    /** Renders [seconds] of a generator, discarding [warmUpSeconds] first. */
    fun renderGenerator(
        generator: SoundGenerator,
        seconds: Double,
        warmUpSeconds: Double = 0.0,
        sampleRate: Int = SAMPLE_RATE,
    ): Capture {
        val blockLeft = FloatArray(BLOCK)
        val blockRight = FloatArray(BLOCK)

        var warmUpFrames = (warmUpSeconds * sampleRate).toInt()
        while (warmUpFrames > 0) {
            val n = minOf(BLOCK, warmUpFrames)
            generator.render(blockLeft, blockRight, n)
            warmUpFrames -= n
        }

        val total = (seconds * sampleRate).toInt()
        val left = FloatArray(total)
        val right = FloatArray(total)
        var written = 0
        while (written < total) {
            val n = minOf(BLOCK, total - written)
            generator.render(blockLeft, blockRight, n)
            System.arraycopy(blockLeft, 0, left, written, n)
            System.arraycopy(blockRight, 0, right, written, n)
            written += n
        }
        return Capture(left, right, sampleRate)
    }

    fun generator(id: SoundId, seed: Long = SEED, sampleRate: Int = SAMPLE_RATE): SoundGenerator =
        Generators.create(id, sampleRate, seed)

    /** Renders [seconds] of a whole [MixRenderer], continuing from its current state. */
    fun renderMix(renderer: MixRenderer, seconds: Double, sampleRate: Int = SAMPLE_RATE): Capture {
        val total = (seconds * sampleRate).toInt()
        val left = FloatArray(total)
        val right = FloatArray(total)
        val blockLeft = FloatArray(BLOCK)
        val blockRight = FloatArray(BLOCK)
        var written = 0
        while (written < total) {
            val n = minOf(BLOCK, total - written)
            renderer.render(blockLeft, blockRight, n)
            System.arraycopy(blockLeft, 0, left, written, n)
            System.arraycopy(blockRight, 0, right, written, n)
            written += n
        }
        return Capture(left, right, sampleRate)
    }

    /** Advances a renderer without keeping the output (used to skip fade-ins). */
    fun advance(renderer: MixRenderer, seconds: Double, sampleRate: Int = SAMPLE_RATE) {
        var remaining = (seconds * sampleRate).toInt()
        val blockLeft = FloatArray(BLOCK)
        val blockRight = FloatArray(BLOCK)
        while (remaining > 0) {
            val n = minOf(BLOCK, remaining)
            renderer.render(blockLeft, blockRight, n)
            remaining -= n
        }
    }

    /** A renderer already started, faded in and holding [mix] at full master volume. */
    fun startedRenderer(
        mix: Mix,
        config: EngineConfig = EngineConfig(sampleRate = SAMPLE_RATE, blockFrames = BLOCK),
        settleSeconds: Double = 3.0,
    ): MixRenderer {
        val renderer = MixRenderer(config, SEED)
        renderer.setMix(mix)
        renderer.setMasterVolume(1f)
        renderer.start()
        advance(renderer, settleSeconds, config.sampleRate)
        return renderer
    }
}
