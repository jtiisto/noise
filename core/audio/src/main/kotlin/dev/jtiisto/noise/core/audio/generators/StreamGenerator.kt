package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.SoundGenerator
import dev.jtiisto.noise.core.audio.dsp.Biquad
import dev.jtiisto.noise.core.audio.dsp.NoiseRng
import dev.jtiisto.noise.core.audio.dsp.bandPassNoiseMakeup
import dev.jtiisto.noise.core.audio.dsp.OnePoleLowPass
import dev.jtiisto.noise.core.audio.dsp.SmoothNoise
import kotlin.math.sqrt

/**
 * @param resonators how many independent "gurgles" make up the brook. Six is
 *        the point where individual voices stop being separable but the
 *        texture is still busy; more just costs CPU.
 * @param wobbleDepth how deeply each resonator's amplitude is modulated.
 */
data class StreamPreset(
    val resonators: Int = 6,
    val lowestHz: Float = 400f,
    val highestHz: Float = 5_000f,
    val qMin: Float = 6f,
    val qMax: Float = 12f,
    val wobbleMinHz: Float = 6f,
    val wobbleMaxHz: Float = 14f,
    val wobbleDepth: Float = 0.4f,
    val driftFraction: Float = 0.08f,
    val panSpread: Float = 0.5f,
    val washCutoffHz: Float = 2_000f,
    /** -10 dB under the resonator bank, per the spec. */
    val washLevel: Float = 0.32f,
    val outputGain: Float = 0.0826f,
) {
    companion object {
        val DEFAULT = StreamPreset()
    }
}

/**
 * Stream — a bank of six wobbling resonators over a broadband wash.
 *
 * Running water is bubbles. Each bubble is a tiny Helmholtz resonator whose
 * pitch is set by its size, and a brook is thousands of them forming and
 * collapsing. Synthesising individual bubbles is possible but expensive; the
 * cheap equivalent that fools the ear is a bank of narrow band-passes on white
 * noise, each one *wobbling* in amplitude at 6-14 Hz. That wobble is the
 * whole trick: static band-passed noise is a vowel, and wobbling band-passed
 * noise is water.
 *
 * Each resonator additionally drifts +/-8 % in centre frequency and is panned
 * to its own position across +/-0.5, so the brook has width and no single
 * pitch stays put long enough to be heard as a tone.
 *
 * Centres are spread geometrically between 400 Hz and 5 kHz — equal *ratios*,
 * not equal hertz, because that is how pitch is perceived and it keeps the
 * bank from bunching up at the top.
 */
class StreamGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: StreamPreset = StreamPreset.DEFAULT,
) : SoundGenerator {

    private val count = preset.resonators.coerceIn(1, 16)
    private val rng = NoiseRng(streamSeed(seed, 71))
    private val washRngLeft = NoiseRng(streamSeed(seed, 72))
    private val washRngRight = NoiseRng(streamSeed(seed, 73))

    // Each resonator gets an independent noise stream and filter *per channel*.
    // Feeding one mono resonator to both ears through pan gains would make the
    // whole bank perfectly correlated, and a brook made of six point sources
    // collapses into the middle of the listener's head.
    private val noiseLeft = Array(count) { NoiseRng(streamSeed(seed, 80 + it)) }
    private val noiseRight = Array(count) { NoiseRng(streamSeed(seed, 120 + it)) }
    private val filtersLeft = Array(count) { Biquad() }
    private val filtersRight = Array(count) { Biquad() }
    private val baseHz = FloatArray(count)
    private val q = FloatArray(count)
    private val panLeft = FloatArray(count)
    private val panRight = FloatArray(count)
    private val level = FloatArray(count)
    private val wobble: Array<SmoothNoise>
    private val drift: Array<SmoothNoise>

    private val washLowPassLeft = OnePoleLowPass(sampleRate, preset.washCutoffHz)
    private val washLowPassRight = OnePoleLowPass(sampleRate, preset.washCutoffHz)

    private val controlRate = (sampleRate / CONTROL_PERIOD).coerceAtLeast(1)
    private var controlCountdown = 0

    init {
        val ratio = if (count > 1) {
            Math.pow(
                (preset.highestHz / preset.lowestHz).toDouble(),
                1.0 / (count - 1),
            ).toFloat()
        } else {
            1f
        }
        var hz = preset.lowestHz
        for (i in 0 until count) {
            baseHz[i] = hz
            hz *= ratio
            q[i] = rng.nextRange(preset.qMin, preset.qMax)
            // Pans alternate outward from the centre rather than sweeping
            // low-to-high across the image. Sweeping would tie pan position to
            // frequency, and since the bank is also tilted down with frequency
            // that would leave the left side permanently louder.
            val sign = if (i % 2 == 0) -1f else 1f
            val pan = preset.panSpread * sign * (1f - (i / 2) * 0.4f)
            panLeft[i] = sqrt(1f - pan)
            panRight[i] = sqrt(1f + pan)
            // Normalise the band-pass's noise gain so every resonator arrives
            // at the same loudness whatever Q and centre it drew, then tilt
            // the bank down slightly with frequency (water is not bright).
            level[i] = bandPassNoiseMakeup(baseHz[i], q[i], sampleRate) *
                (1f - 0.35f * i / count.toFloat())
        }
        wobble = Array(count) {
            SmoothNoise(sampleRate, rng, rng.nextRange(preset.wobbleMinHz, preset.wobbleMaxHz))
        }
        drift = Array(count) { SmoothNoise(controlRate, rng, DRIFT_HZ) }
        retune()
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain
        val depth = preset.wobbleDepth
        val washLevel = preset.washLevel
        val n = count

        for (i in 0 until frames) {
            if (--controlCountdown <= 0) {
                controlCountdown = CONTROL_PERIOD
                retune()
            }

            var l = washLowPassLeft.process(washRngLeft.nextGaussian()) * washLevel
            var r = washLowPassRight.process(washRngRight.nextGaussian()) * washLevel

            for (v in 0 until n) {
                val amplitude = (1f + depth * wobble[v].next()) * level[v]
                l += filtersLeft[v].process(noiseLeft[v].nextFloat()) * amplitude * panLeft[v]
                r += filtersRight[v].process(noiseRight[v].nextFloat()) * amplitude * panRight[v]
            }

            left[i] = l * gain
            right[i] = r * gain
        }
    }

    override fun reset(seed: Long) {
        rng.reseed(streamSeed(seed, 71))
        washRngLeft.reseed(streamSeed(seed, 72))
        washRngRight.reseed(streamSeed(seed, 73))
        for (i in 0 until count) {
            noiseLeft[i].reseed(streamSeed(seed, 80 + i))
            noiseRight[i].reseed(streamSeed(seed, 120 + i))
            filtersLeft[i].reset()
            filtersRight[i].reset()
            wobble[i].reset()
            drift[i].reset()
        }
        washLowPassLeft.reset()
        washLowPassRight.reset()
        controlCountdown = 0
        retune()
    }

    private fun retune() {
        val fraction = preset.driftFraction
        for (v in 0 until count) {
            val hz = baseHz[v] * (1f + fraction * drift[v].next())
            filtersLeft[v].setBandPass(sampleRate, hz, q[v])
            filtersRight[v].setBandPass(sampleRate, hz, q[v])
        }
    }

    private companion object {
        const val CONTROL_PERIOD = 64
        const val DRIFT_HZ = 0.15f
    }
}
