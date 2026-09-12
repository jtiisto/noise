package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.SoundGenerator
import dev.jtiisto.noise.core.audio.dsp.Biquad
import dev.jtiisto.noise.core.audio.dsp.DualExponential
import dev.jtiisto.noise.core.audio.dsp.NoiseRng
import dev.jtiisto.noise.core.audio.dsp.OnePoleLowPass
import dev.jtiisto.noise.core.audio.dsp.PoissonClock
import dev.jtiisto.noise.core.audio.dsp.SmoothNoise
import dev.jtiisto.noise.core.audio.dsp.bandPassNoiseMakeup
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * @param resonators how many bubble *size classes* the brook has. Six is the
 *        point where individual pitches stop being separable but the texture
 *        is still busy; more just costs CPU.
 * @param burstsPerSecondMin / [burstsPerSecondMax] Poisson rate of bubbles in
 *        each size class, drawn once per resonator.
 * @param burstMinMs / [burstMaxMs] how long one bubble rings, drawn once per
 *        resonator — a size class has a consistent decay because the decay is
 *        set by the bubble's size.
 * @param sweepDepth how far *below* its resting pitch a bubble starts, as a
 *        fraction. A rising bubble's resonant frequency climbs as it nears the
 *        surface; this upward chirp is the difference between "gloop" and
 *        "click".
 * @param driftFraction / [driftRateMinHz] / [driftRateMaxHz] the wander of
 *        each size class's resting pitch — wide and fast enough to see moving
 *        on a spectrogram.
 */
data class StreamPreset(
    val resonators: Int = 6,
    val lowestHz: Float = 400f,
    val highestHz: Float = 5_000f,
    val qMin: Float = 3f,
    val qMax: Float = 5f,
    val burstsPerSecondMin: Float = 30f,
    val burstsPerSecondMax: Float = 70f,
    val burstMinMs: Float = 15f,
    val burstMaxMs: Float = 60f,
    val sweepDepth: Float = 0.35f,
    val driftFraction: Float = 0.20f,
    val driftRateMinHz: Float = 0.3f,
    val driftRateMaxHz: Float = 1.0f,
    val panSpread: Float = 0.5f,
    val washCutoffHz: Float = 2_000f,
    /** -10 dB under the resonator bank, per the spec. */
    val washLevel: Float = 0.32f,
    val outputGain: Float = 0.2020f,
) {
    companion object {
        val DEFAULT = StreamPreset()
    }
}

/**
 * Stream — a Poisson rain of short, upward-chirping bubbles across six size
 * classes, over a broadband wash.
 *
 * Running water is bubbles. Each is a tiny Helmholtz resonator whose pitch is
 * set by its radius, it rings for a few tens of milliseconds, and its pitch
 * *rises* as it ascends and the bubble shrinks under falling pressure. A brook
 * is thousands of them per second across a range of sizes.
 *
 * The first version of this generator drove six band-passes with *continuous*
 * white noise and wobbled their amplitude. That is the standard cheap
 * approximation and it is wrong in a way that shows up immediately on a
 * spectrogram: six perfectly steady horizontal lines for twenty seconds. The
 * ear hears that as a filtered drone — a vowel — not as water, because water
 * has no sustained partials at all. What it has is *onsets*.
 *
 * So the excitation is impulsive:
 *
 *  * each resonator gets its own **Poisson clock** (20-60 bubbles/s) and its
 *    own difference-of-exponentials envelope. Triggers *add* into the envelope
 *    state, so overlapping bubbles sum correctly and the result is a
 *    continuously fluctuating excitation with distinct attacks rather than a
 *    steady level;
 *  * every trigger also kicks a **sweep** state to 1, which decays with the
 *    bubble's own time constant and pulls the resonator's centre frequency
 *    down by [StreamPreset.sweepDepth] and back up — the rising chirp;
 *  * **Q is 3-5**, not 6-12. A high-Q resonator rings long enough to become a
 *    tone; a broad one just colours the burst, which is what a real bubble
 *    does;
 *  * the resting pitch of each class wanders +/-20 % at 0.3-1 Hz, fast and wide
 *    enough that the bands visibly move.
 *
 * Under all of it, a quiet broadband wash below 2 kHz: the sheet of water that
 * is not bubbling.
 *
 * Each resonator has independent noise *and filters per channel* under a
 * shared envelope: one bubble, two ears. Feeding a single mono resonator to
 * both ears through pan gains left the whole bank 0.91-correlated and
 * collapsed the brook into the middle of the listener's head. Pans alternate
 * outward from the centre rather than sweeping low-to-high, because sweeping
 * would tie pan position to frequency and the bank's downward tilt would then
 * leave the left side permanently louder.
 *
 * Centres are spread geometrically between 400 Hz and 5 kHz — equal *ratios*,
 * because that is how pitch is perceived and it keeps the bank from bunching
 * up at the top.
 */
class StreamGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: StreamPreset = StreamPreset.DEFAULT,
) : SoundGenerator {

    private val count = preset.resonators.coerceIn(1, 16)
    private val rng = NoiseRng(streamSeed(seed, 71))
    private val burstRng = NoiseRng(streamSeed(seed, 74))
    private val washRngLeft = NoiseRng(streamSeed(seed, 72))
    private val washRngRight = NoiseRng(streamSeed(seed, 73))

    private val noiseLeft = Array(count) { NoiseRng(streamSeed(seed, 80 + it)) }
    private val noiseRight = Array(count) { NoiseRng(streamSeed(seed, 120 + it)) }
    private val filtersLeft = Array(count) { Biquad() }
    private val filtersRight = Array(count) { Biquad() }

    private val baseHz = FloatArray(count)
    private val q = FloatArray(count)
    private val panLeft = FloatArray(count)
    private val panRight = FloatArray(count)
    private val level = FloatArray(count)

    // Bubble stream state, one entry per size class.
    private val clocks: Array<PoissonClock>
    private val drift: Array<SmoothNoise>
    private val burstRate = FloatArray(count)
    private val decaySlow = FloatArray(count)
    private val decayFast = FloatArray(count)
    private val sweepDecay = FloatArray(count)
    private val envSlow = FloatArray(count)
    private val envFast = FloatArray(count)
    private val sweep = FloatArray(count)

    private val washLowPassLeft = OnePoleLowPass(sampleRate, preset.washCutoffHz)
    private val washLowPassRight = OnePoleLowPass(sampleRate, preset.washCutoffHz)

    private val controlRate = (sampleRate / CONTROL_PERIOD).coerceAtLeast(1)
    private var controlCountdown = 0

    /** Bubbles started since construction / [reset]; used by tests. */
    val bubbleCount: Long
        get() {
            var n = 0L
            for (i in 0 until count) n += bubbles[i]
            return n
        }

    private val bubbles = LongArray(count)

    init {
        val ratio = if (count > 1) {
            (preset.highestHz / preset.lowestHz).toDouble()
                .pow(1.0 / (count - 1)).toFloat()
        } else {
            1f
        }
        var hz = preset.lowestHz
        for (i in 0 until count) {
            baseHz[i] = hz
            hz *= ratio
            q[i] = rng.nextRange(preset.qMin, preset.qMax)
            val sign = if (i % 2 == 0) -1f else 1f
            val pan = preset.panSpread * sign * (1f - (i / 2) * 0.4f)
            panLeft[i] = sqrt(1f - pan)
            panRight[i] = sqrt(1f + pan)
        }
        clocks = Array(count) { PoissonClock(sampleRate, burstRng, preset.burstsPerSecondMin) }
        drift = Array(count) {
            SmoothNoise(
                controlRate,
                rng,
                rng.nextRange(preset.driftRateMinHz, preset.driftRateMaxHz),
            )
        }
        configureBubbles()
        retune()
    }

    private fun configureBubbles() {
        for (i in 0 until count) {
            burstRate[i] = rng.nextRange(preset.burstsPerSecondMin, preset.burstsPerSecondMax)
            clocks[i].setRate(burstRate[i])
            val burstSeconds = rng.nextRange(preset.burstMinMs, preset.burstMaxMs) / 1000f
            val burstSamples = burstSeconds * sampleRate
            decaySlow[i] = DualExponential.decayCoefficient(burstSamples)
            decayFast[i] = DualExponential.decayCoefficient(burstSamples / ATTACK_RATIO)
            sweepDecay[i] = DualExponential.decayCoefficient(burstSamples)
            envSlow[i] = 0f
            envFast[i] = 0f
            sweep[i] = 0f
            bubbles[i] = 0L
            // Two normalisations, so that rate, duration, Q and centre all
            // shape texture without also shaping loudness:
            //  - the band-pass's noise gain, and
            //  - the mean square of a Poisson train of these pulses, which is
            //    proportional to rate x tau (the 0.34 is the integral of the
            //    squared difference-of-exponentials for an 8:1 attack ratio).
            val trainRms = sqrt(burstRate[i] * burstSeconds * PULSE_ENERGY)
            level[i] = bandPassNoiseMakeup(baseHz[i], q[i], sampleRate) /
                trainRms.coerceAtLeast(1e-3f) *
                (1f - 0.35f * i / count.toFloat())
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain
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
                if (clocks[v].tick()) {
                    // Amplitude squared-uniform: most bubbles are small, a few
                    // are not, which is what gives a brook its irregular
                    // foreground without any of it being loud.
                    val amplitude = 0.35f + 0.65f * burstRng.nextUnit()
                    envSlow[v] += amplitude
                    envFast[v] += amplitude
                    sweep[v] = 1f
                    bubbles[v]++
                }
                val envelope = envSlow[v] - envFast[v]
                envSlow[v] *= decaySlow[v]
                envFast[v] *= decayFast[v]
                sweep[v] *= sweepDecay[v]

                val excitationLeft = noiseLeft[v].nextFloat() * envelope
                val excitationRight = noiseRight[v].nextFloat() * envelope
                // The filters are always run, even between bubbles, so a
                // resonance rings out instead of being cut off.
                l += filtersLeft[v].process(excitationLeft) * level[v] * panLeft[v]
                r += filtersRight[v].process(excitationRight) * level[v] * panRight[v]
            }

            left[i] = l * gain
            right[i] = r * gain
        }
    }

    override fun reset(seed: Long) {
        rng.reseed(streamSeed(seed, 71))
        burstRng.reseed(streamSeed(seed, 74))
        washRngLeft.reseed(streamSeed(seed, 72))
        washRngRight.reseed(streamSeed(seed, 73))
        for (i in 0 until count) {
            noiseLeft[i].reseed(streamSeed(seed, 80 + i))
            noiseRight[i].reseed(streamSeed(seed, 120 + i))
            filtersLeft[i].reset()
            filtersRight[i].reset()
            drift[i].reset()
            // Restore the clock's rate before resetting it: the first gap
            // after a reset is drawn from whatever rate is current, so
            // reset(seed) would otherwise not be reproducible.
            clocks[i].setRate(preset.burstsPerSecondMin)
            clocks[i].reset()
        }
        washLowPassLeft.reset()
        washLowPassRight.reset()
        controlCountdown = 0
        configureBubbles()
        for (i in 0 until count) clocks[i].setRate(burstRate[i])
        retune()
    }

    private fun retune() {
        val fraction = preset.driftFraction
        val sweepDepth = preset.sweepDepth
        for (v in 0 until count) {
            // Resting pitch wanders; each bubble pulls it down and lets it
            // climb back, which is the rising chirp.
            val hz = baseHz[v] * (1f + fraction * drift[v].next()) * (1f - sweepDepth * sweep[v])
            filtersLeft[v].setBandPass(sampleRate, hz, q[v])
            filtersRight[v].setBandPass(sampleRate, hz, q[v])
        }
    }

    private companion object {
        const val CONTROL_PERIOD = 64
        /**
         * Attack is 1/5 of the decay. Sharper (8:1, as the drop and crackle
         * pools use) gives a spikier onset and a crest factor that eats the
         * mix's headroom; a bubble is a resonance being filled, not a click.
         */
        const val ATTACK_RATIO = 5f
        /** Integral of `(e^-t/tau - e^-r t/tau)^2` in units of tau, for r = ATTACK_RATIO. */
        const val PULSE_ENERGY = 0.5f - 2f / (1f + ATTACK_RATIO) + 1f / (2f * ATTACK_RATIO)
    }
}
