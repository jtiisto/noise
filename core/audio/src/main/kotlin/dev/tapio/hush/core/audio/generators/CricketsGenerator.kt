package dev.tapio.hush.core.audio.generators

import dev.tapio.hush.core.audio.SoundGenerator
import dev.tapio.hush.core.audio.dsp.FastSine
import dev.tapio.hush.core.audio.dsp.NoiseRng
import dev.tapio.hush.core.audio.dsp.OnePoleHighPass
import dev.tapio.hush.core.audio.dsp.OnePoleLowPass
import dev.tapio.hush.core.audio.dsp.PinkFilter
import kotlin.math.sqrt

/**
 * @param individuals how many crickets are in earshot. Four is enough for the
 *        field to sound populated without turning into a wall of tone.
 * @param bedLevel level of the pink night-air bed. Quiet, but removing it
 *        entirely leaves the chirps floating in a vacuum.
 */
data class CricketsPreset(
    val individuals: Int = 4,
    val carrierMinHz: Float = 3_600f,
    val carrierMaxHz: Float = 5_200f,
    val trillMinHz: Float = 28f,
    val trillMaxHz: Float = 42f,
    val chirpOnMinMs: Float = 80f,
    val chirpOnMaxMs: Float = 160f,
    val chirpOffMinMs: Float = 150f,
    val chirpOffMaxMs: Float = 350f,
    val burstMin: Int = 3,
    val burstMax: Int = 7,
    val pauseMinSeconds: Float = 1f,
    val pauseMaxSeconds: Float = 4f,
    /** -24 dB under the chirps, per the spec. */
    val bedLevel: Float = 0.063f,
    /**
     * The night-air bed is high-passed: a full-range pink bed puts most of its
     * energy below 200 Hz, where a summer field has nothing and a phone
     * speaker has no output either.
     */
    val bedHighPassHz: Float = 250f,
    val outputGain: Float = 0.208f,
) {
    companion object {
        val DEFAULT = CricketsPreset()
    }
}

/**
 * Crickets — four independent stridulation models over a pink night bed.
 *
 * A cricket chirp is a nearly pure tone (the wing's file-and-scraper resonates
 * at one frequency) that is amplitude-modulated at the wing-stroke rate. So
 * each individual is:
 *
 *  * a **carrier** sine, 3.6-5.2 kHz, fixed per individual — its species and
 *    body size;
 *  * a **trill**, 28-42 Hz at full depth: the individual wing strokes, which
 *    is what makes a chirp buzz rather than beep;
 *  * a **chirp gate**, 80-160 ms on / 150-350 ms off, grouped into bursts of
 *    3-7 with a 1-4 s pause. Real crickets chirp in phrases, and the pauses
 *    are what let the ear hear four separate animals instead of one texture.
 *
 * The gate is smoothed by a 4 ms one-pole; switching a 4 kHz sine on in one
 * sample is a click, and a night full of clicks is the opposite of restful.
 *
 * This generator is calibrated to [TONAL_TARGET_RMS] (-26 dBFS) rather than
 * the usual -20: narrow-band tonal content at equal RMS sounds dramatically
 * louder than a broadband bed, and at -20 dBFS crickets would dominate any
 * mix they were added to.
 */
class CricketsGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: CricketsPreset = CricketsPreset.DEFAULT,
) : SoundGenerator {

    private val count = preset.individuals.coerceIn(1, 12)
    private val rng = NoiseRng(streamSeed(seed, 91))
    private val bedRngLeft = NoiseRng(streamSeed(seed, 92))
    private val bedRngRight = NoiseRng(streamSeed(seed, 93))
    private val bedLeft = PinkFilter()
    private val bedRight = PinkFilter()
    private val bedHighPassLeft = OnePoleHighPass(sampleRate, preset.bedHighPassHz)
    private val bedHighPassRight = OnePoleHighPass(sampleRate, preset.bedHighPassHz)

    private val carrierPhase = FloatArray(count)
    private val carrierIncrement = FloatArray(count)
    private val trillPhase = FloatArray(count)
    private val trillIncrement = FloatArray(count)
    private val panLeft = FloatArray(count)
    private val panRight = FloatArray(count)
    private val level = FloatArray(count)

    private val gateSamplesLeft = IntArray(count)
    private val gateOpen = BooleanArray(count)
    private val chirpsLeft = IntArray(count)
    private val gateSmoother = Array(count) { OnePoleLowPass(sampleRate, GATE_SMOOTH_HZ) }

    init {
        configureIndividuals()
    }

    private fun configureIndividuals() {
        for (i in 0 until count) {
            carrierIncrement[i] = rng.nextRange(preset.carrierMinHz, preset.carrierMaxHz) /
                sampleRate
            trillIncrement[i] = rng.nextRange(preset.trillMinHz, preset.trillMaxHz) / sampleRate
            carrierPhase[i] = rng.nextUnit()
            trillPhase[i] = rng.nextUnit()
            val pan = rng.nextRange(-0.8f, 0.8f)
            panLeft[i] = sqrt(1f - pan)
            panRight[i] = sqrt(1f + pan)
            // Distance: some crickets are further away than others.
            level[i] = rng.nextRange(0.35f, 1f)
            chirpsLeft[i] = rng.nextInt(preset.burstMax - preset.burstMin + 1) + preset.burstMin
            gateOpen[i] = false
            gateSamplesLeft[i] = (rng.nextRange(0f, preset.pauseMaxSeconds) * sampleRate).toInt()
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain
        val bedLevel = preset.bedLevel
        val n = count

        for (i in 0 until frames) {
            var l = bedHighPassLeft.process(bedLeft.process(bedRngLeft.nextFloat())) * bedLevel
            var r = bedHighPassRight.process(bedRight.process(bedRngRight.nextFloat())) * bedLevel

            for (v in 0 until n) {
                if (--gateSamplesLeft[v] <= 0) advanceGate(v)
                val target = if (gateOpen[v]) 1f else 0f
                val gate = gateSmoother[v].process(target)
                if (gate > 1e-4f) {
                    // Full-depth trill: (1 - cos)/2 keeps the modulator
                    // non-negative, so the chirp pulses rather than inverting
                    // phase (which would halve the perceived pitch).
                    val trill = 0.5f * (1f - FastSine.at(trillPhase[v] + 0.25f))
                    val s = FastSine.at(carrierPhase[v]) * trill * gate * level[v]
                    l += s * panLeft[v]
                    r += s * panRight[v]
                }
                var cp = carrierPhase[v] + carrierIncrement[v]
                if (cp >= 1f) cp -= 1f
                carrierPhase[v] = cp
                var tp = trillPhase[v] + trillIncrement[v]
                if (tp >= 1f) tp -= 1f
                trillPhase[v] = tp
            }

            left[i] = l * gain
            right[i] = r * gain
        }
    }

    override fun reset(seed: Long) {
        rng.reseed(streamSeed(seed, 91))
        bedRngLeft.reseed(streamSeed(seed, 92))
        bedRngRight.reseed(streamSeed(seed, 93))
        bedLeft.reset()
        bedRight.reset()
        bedHighPassLeft.reset()
        bedHighPassRight.reset()
        for (i in 0 until count) gateSmoother[i].reset()
        configureIndividuals()
    }

    /** Walks one individual through chirp -> gap -> chirp ... -> phrase pause. */
    private fun advanceGate(v: Int) {
        if (gateOpen[v]) {
            gateOpen[v] = false
            chirpsLeft[v]--
            gateSamplesLeft[v] = if (chirpsLeft[v] > 0) {
                (rng.nextRange(preset.chirpOffMinMs, preset.chirpOffMaxMs) * sampleRate / 1000f)
                    .toInt()
            } else {
                chirpsLeft[v] =
                    rng.nextInt(preset.burstMax - preset.burstMin + 1) + preset.burstMin
                (rng.nextRange(preset.pauseMinSeconds, preset.pauseMaxSeconds) * sampleRate).toInt()
            }
        } else {
            gateOpen[v] = true
            gateSamplesLeft[v] =
                (rng.nextRange(preset.chirpOnMinMs, preset.chirpOnMaxMs) * sampleRate / 1000f)
                    .toInt()
        }
        if (gateSamplesLeft[v] < 1) gateSamplesLeft[v] = 1
    }

    private companion object {
        /** 4 ms attack/release on the chirp gate — enough to kill the click. */
        const val GATE_SMOOTH_HZ = 40f
    }
}
