package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.SoundGenerator
import dev.jtiisto.noise.core.audio.dsp.Biquad
import dev.jtiisto.noise.core.audio.dsp.BrownNoiseSource
import dev.jtiisto.noise.core.audio.dsp.NoiseBurstVoicePool
import dev.jtiisto.noise.core.audio.dsp.NoiseRng
import dev.jtiisto.noise.core.audio.dsp.OnePoleLowPass
import dev.jtiisto.noise.core.audio.dsp.PoissonClock
import dev.jtiisto.noise.core.audio.dsp.SmoothNoise

/**
 * Tuning knobs for [RainGenerator]. The catalog's Rain and Downpour are two
 * presets of the same synth, and the thunderstorm reuses the downpour preset
 * at a slightly lower level to leave headroom for the rolls.
 *
 * @param dropsPerSecond mean Poisson rate of individual audible drops.
 * @param bedLowHz / [bedHighHz] band-pass corners of the continuous sheet.
 * @param bedLevel amplitude of the sheet before the output gain.
 * @param dropLevel mean peak amplitude of one drop.
 * @param dropMinMs / [dropMaxMs] decay time (to -40 dB) of a drop.
 * @param dropMinCutoffHz / [dropMaxCutoffHz] per-drop low-pass colour.
 * @param bodyLevel level of the distant low-frequency wash.
 * @param gustDepth how much the slow random walk swings the sheet, 0..1.
 * @param outputGain final calibration multiplier (measured, see [TARGET_RMS]).
 */
data class RainPreset(
    val dropsPerSecond: Float,
    val bedLowHz: Float,
    val bedHighHz: Float,
    val bedLevel: Float,
    val dropLevel: Float,
    val dropMinMs: Float,
    val dropMaxMs: Float,
    val dropMinCutoffHz: Float,
    val dropMaxCutoffHz: Float,
    val bodyLevel: Float,
    val bodyCutoffHz: Float = 700f,
    /** Sub-audio corner of the distant body; everything below is wasted excursion. */
    val bodyHighPassHz: Float = 45f,
    val gustRateHz: Float = 0.12f,
    val gustDepth: Float = 0.35f,
    val voices: Int = 24,
    val outputGain: Float,
) {
    companion object {
        /** Steady rain on leaves: sparse, individually audible drops over a soft sheet. */
        val LIGHT = RainPreset(
            dropsPerSecond = 60f,
            bedLowHz = 1_200f,
            bedHighHz = 6_000f,
            bedLevel = 1.0f,
            dropLevel = 0.85f,
            dropMinMs = 2f,
            dropMaxMs = 12f,
            dropMinCutoffHz = 2_000f,
            dropMaxCutoffHz = 9_000f,
            bodyLevel = 0.14f,
            outputGain = 0.2155f,
        )

        /**
         * Downpour: three times the drop rate, a brighter and wider sheet and
         * twice the low body. Past roughly 200 drops/s individual drops stop
         * being separable and the ear hears a sheet, which is exactly what
         * heavy rain sounds like — the extra rate is spent on texture density
         * rather than on audible ticks.
         */
        val DOWNPOUR = RainPreset(
            dropsPerSecond = 200f,
            bedLowHz = 800f,
            bedHighHz = 8_000f,
            bedLevel = 1.35f,
            dropLevel = 0.55f,
            dropMinMs = 2f,
            dropMaxMs = 9f,
            dropMinCutoffHz = 2_500f,
            dropMaxCutoffHz = 11_000f,
            bodyLevel = 0.30f,
            gustDepth = 0.28f,
            outputGain = 0.1278f,
        )
    }
}

/**
 * Rain — three superimposed layers, the standard Farnell decomposition of a
 * "many small events" texture:
 *
 *  1. **The sheet.** Gaussian noise band-passed to 1.5-6 kHz by two biquads.
 *     This is the sum of the thousands of drops too far away or too small to
 *     resolve; the central limit theorem says that sum is Gaussian, which is
 *     why the bed uses `nextGaussian` and not the cheaper uniform draw — flat
 *     -topped uniform noise reads as "hiss", Gaussian reads as "rain".
 *     A 0.05-0.2 Hz random walk swings its amplitude so the shower breathes
 *     instead of sitting still, which is the single biggest cue that a sound
 *     is a loop or a synthetic bed.
 *  2. **The drops.** A Poisson stream of short noise bursts, each with its own
 *     decay time, low-pass colour, level and pan. Randomising *all four*
 *     matters: constant-timbre drops sound like a Geiger counter.
 *  3. **The body.** Brown noise at 300 Hz and below, quiet — the rumble of
 *     rain on ground and roofs a street away. Without it the sound has no
 *     depth and sits inside the listener's head.
 *
 * Left and right have independent noise streams for layers 1 and 3 and share
 * only the gust envelope, because a gust is a real-world event that happens at
 * both ears; individual drops are panned per event.
 */
class RainGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: RainPreset = RainPreset.LIGHT,
    /** Extra level trim so the thunderstorm can duck its own bed. */
    private val levelTrim: Float = 1f,
) : SoundGenerator {

    private val bedRngLeft = NoiseRng(streamSeed(seed, 21))
    private val bedRngRight = NoiseRng(streamSeed(seed, 22))
    private val bodyRngLeft = NoiseRng(streamSeed(seed, 23))
    private val bodyRngRight = NoiseRng(streamSeed(seed, 24))
    private val dropRng = NoiseRng(streamSeed(seed, 25))
    private val gustRng = NoiseRng(streamSeed(seed, 26))

    private val bedHighPassLeft = Biquad()
    private val bedHighPassRight = Biquad()
    private val bedLowPassLeft = Biquad()
    private val bedLowPassRight = Biquad()

    // The body is brown noise, but rolled off below 45 Hz: content down
    // there is inaudible on a phone speaker and only eats headroom.
    private val bodyLeft = BrownNoiseSource(sampleRate, bodyRngLeft, 10f, preset.bodyHighPassHz)
    private val bodyRight = BrownNoiseSource(sampleRate, bodyRngRight, 10f, preset.bodyHighPassHz)
    private val bodyLowPassLeft = OnePoleLowPass(sampleRate, preset.bodyCutoffHz)
    private val bodyLowPassRight = OnePoleLowPass(sampleRate, preset.bodyCutoffHz)

    private val gust = SmoothNoise(sampleRate, gustRng, preset.gustRateHz)
    private val dropClock = PoissonClock(sampleRate, dropRng, preset.dropsPerSecond)
    private val drops = NoiseBurstVoicePool(preset.voices, dropRng, sampleRate)

    /** Drops started since construction / [reset] — the drop-rate assertion's probe. */
    val dropCount: Long get() = drops.spawnCount

    init {
        configureBed()
    }

    private fun configureBed() {
        // Two cascaded 2nd-order sections give the 24 dB/oct skirts that make
        // the band read as "up in the trees" rather than as full-range hiss.
        bedHighPassLeft.setHighPass(sampleRate, preset.bedLowHz, BED_Q)
        bedHighPassRight.setHighPass(sampleRate, preset.bedLowHz, BED_Q)
        bedLowPassLeft.setLowPass(sampleRate, preset.bedHighHz, BED_Q)
        bedLowPassRight.setLowPass(sampleRate, preset.bedHighHz, BED_Q)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain * levelTrim
        val bedLevel = preset.bedLevel
        val bodyLevel = preset.bodyLevel
        val gustDepth = preset.gustDepth
        val dropLevel = preset.dropLevel
        val minDecay = preset.dropMinMs * sampleRate / 1000f
        val maxDecay = preset.dropMaxMs * sampleRate / 1000f
        val minCutoff = preset.dropMinCutoffHz
        val maxCutoff = preset.dropMaxCutoffHz

        for (i in 0 until frames) {
            val gustGain = 1f + gustDepth * gust.next()

            var l = bedLowPassLeft.process(bedHighPassLeft.process(bedRngLeft.nextGaussian()))
            var r = bedLowPassRight.process(bedHighPassRight.process(bedRngRight.nextGaussian()))
            l *= bedLevel * gustGain
            r *= bedLevel * gustGain

            l += bodyLowPassLeft.process(bodyLeft.next()) * bodyLevel
            r += bodyLowPassRight.process(bodyRight.next()) * bodyLevel

            left[i] = l
            right[i] = r

            if (dropClock.tick()) {
                // A drop's level and its brightness are correlated in nature
                // (bigger drop = louder and duller); randomising them
                // independently is close enough and much simpler, but the
                // level uses a squared uniform so quiet drops dominate and
                // the occasional loud one stands out.
                val u = dropRng.nextUnit()
                val amplitude = dropLevel * (0.15f + 0.85f * u * u) * gustGain
                val decay = dropRng.nextRange(minDecay, maxDecay).toInt()
                val cutoff = dropRng.nextRange(minCutoff, maxCutoff)
                drops.spawn(decay, amplitude, dropRng.nextFloat(), cutoff)
            }
            // The pool accumulates into the output buffer we just wrote, so
            // beds and drops share one accumulator and no scratch array is
            // needed; the calibration gain is applied last, to everything.
            drops.addSample(left, right, i)
            left[i] *= gain
            right[i] *= gain
        }
    }

    override fun reset(seed: Long) {
        bedRngLeft.reseed(streamSeed(seed, 21))
        bedRngRight.reseed(streamSeed(seed, 22))
        bodyRngLeft.reseed(streamSeed(seed, 23))
        bodyRngRight.reseed(streamSeed(seed, 24))
        dropRng.reseed(streamSeed(seed, 25))
        gustRng.reseed(streamSeed(seed, 26))
        bedHighPassLeft.reset()
        bedHighPassRight.reset()
        bedLowPassLeft.reset()
        bedLowPassRight.reset()
        bodyLeft.reset()
        bodyRight.reset()
        bodyLowPassLeft.reset()
        bodyLowPassRight.reset()
        gust.reset()
        dropClock.reset()
        drops.reset()
    }

    private companion object {
        const val BED_Q = 0.707f
    }
}
