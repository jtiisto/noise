package dev.tapio.hush.core.audio.generators

import dev.tapio.hush.core.audio.SoundGenerator
import dev.tapio.hush.core.audio.dsp.Biquad
import dev.tapio.hush.core.audio.dsp.BrownNoiseSource
import dev.tapio.hush.core.audio.dsp.NoiseRng
import dev.tapio.hush.core.audio.dsp.OnePoleLowPass
import dev.tapio.hush.core.audio.dsp.PoissonClock
import dev.tapio.hush.core.audio.dsp.RandomWalk
import dev.tapio.hush.core.audio.dsp.SmoothNoise
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * @param howlQ resonance of the main voice. Around 4 the band is narrow enough
 *        to have a pitch you can follow but wide enough not to ring.
 * @param swellMeanSeconds mean gap between the big 3-8 s gusts that ride on
 *        top of the continuous wander.
 */
data class WindPreset(
    val howlMinHz: Float = 250f,
    val howlMaxHz: Float = 700f,
    val howlQ: Float = 4f,
    val howlWalkHz: Float = 0.1f,
    val whistleMinHz: Float = 1_200f,
    val whistleMaxHz: Float = 2_500f,
    val whistleQ: Float = 6f,
    val whistleWalkHz: Float = 0.07f,
    /** -12 dB under the howl, per the spec. */
    val whistleLevel: Float = 0.25f,
    val gustDepth: Float = 0.40f,
    val gustRateHz: Float = 0.09f,
    val swellMeanSeconds: Float = 9f,
    val swellMinSeconds: Float = 3f,
    val swellMaxSeconds: Float = 8f,
    val panDrift: Float = 0.3f,
    /**
     * Low-frequency buffet: the pressure fluctuation of moving air against
     * whatever the listener is inside. It is gated by the gust envelope
     * *squared*, so it only arrives with the strong gusts, which is what makes
     * a gust feel like weight rather than just more hiss.
     */
    val buffetLevel: Float = 0.020f,
    val buffetHighPassHz: Float = 25f,
    val buffetLowPassHz: Float = 90f,
    val outputGain: Float = 2.82f,
) {
    companion object {
        val DEFAULT = WindPreset()
    }
}

/**
 * Wind — two resonant voices sharing one gust envelope.
 *
 * Wind has no sound of its own; what we hear is air exciting resonances in
 * whatever it passes. So the synthesis is white noise through band-passes
 * whose centre frequencies wander: a low, broad "howl" (250-700 Hz, the sound
 * of a large opening) and a quiet high "whistle" (1.2-2.5 kHz, the sound of an
 * edge). Each wanders on its own slow random walk, because they are different
 * objects being excited.
 *
 * Both are multiplied by a shared gust envelope — a continuous slow wander
 * with occasional raised-cosine swells 3-8 s long dropped on top by a Poisson
 * clock. The two time scales together are what makes wind sound alive: the
 * wander alone is too even, the swells alone are too periodic.
 *
 * Finally the whole thing pans slowly by +/-0.3. Wind moves; a static stereo
 * image would place it inside a room.
 */
class WindGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: WindPreset = WindPreset.DEFAULT,
) : SoundGenerator {

    private val rng = NoiseRng(streamSeed(seed, 51))
    private val howlRngLeft = NoiseRng(streamSeed(seed, 52))
    private val howlRngRight = NoiseRng(streamSeed(seed, 53))
    private val whistleRngLeft = NoiseRng(streamSeed(seed, 54))
    private val whistleRngRight = NoiseRng(streamSeed(seed, 55))
    private val buffetRngLeft = NoiseRng(streamSeed(seed, 56))
    private val buffetRngRight = NoiseRng(streamSeed(seed, 57))

    private val howlLeft = Biquad()
    private val howlRight = Biquad()
    private val whistleLeft = Biquad()
    private val whistleRight = Biquad()

    // The walks are advanced once per control block, so they are constructed
    // at the control rate and their frequencies stay honest wall-clock hertz.
    private val controlRate = (sampleRate / CONTROL_PERIOD).coerceAtLeast(1)
    private val howlWalk =
        RandomWalk(controlRate, rng, preset.howlMinHz, preset.howlMaxHz, preset.howlWalkHz)
    private val whistleWalk =
        RandomWalk(controlRate, rng, preset.whistleMinHz, preset.whistleMaxHz, preset.whistleWalkHz)
    private val buffetLeft =
        BrownNoiseSource(sampleRate, buffetRngLeft, 10f, preset.buffetHighPassHz)
    private val buffetRight =
        BrownNoiseSource(sampleRate, buffetRngRight, 10f, preset.buffetHighPassHz)
    private val buffetLowPassLeft = OnePoleLowPass(sampleRate, preset.buffetLowPassHz)
    private val buffetLowPassRight = OnePoleLowPass(sampleRate, preset.buffetLowPassHz)

    private val gustNoise = SmoothNoise(sampleRate, rng, preset.gustRateHz)
    private val panNoise = SmoothNoise(sampleRate, rng, PAN_RATE_HZ)
    private val swellClock = PoissonClock(sampleRate, rng, 1f / preset.swellMeanSeconds)

    private var swellSamplesLeft = 0
    private var swellLength = 1
    private var swellAmplitude = 0f
    private var controlCountdown = 0

    init {
        retune()
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain
        val whistleLevel = preset.whistleLevel
        val gustDepth = preset.gustDepth
        val panDrift = preset.panDrift

        for (i in 0 until frames) {
            if (--controlCountdown <= 0) {
                controlCountdown = CONTROL_PERIOD
                retune()
            }

            if (swellSamplesLeft <= 0 && swellClock.tick()) startSwell()
            var swell = 0f
            if (swellSamplesLeft > 0) {
                val p = 1f - swellSamplesLeft.toFloat() / swellLength
                // Raised cosine: a swell must arrive and leave without a corner.
                swell = swellAmplitude * 0.5f * (1f - cos(2.0 * PI * p).toFloat())
                swellSamplesLeft--
            }

            val gust = (GUST_BASE + gustDepth * gustNoise.next() + swell).coerceIn(0.02f, 1.8f)

            val pan = panDrift * panNoise.next()
            // Equal-power pan normalised so centre is unity on both sides.
            val panL = sqrt(1f - pan)
            val panR = sqrt(1f + pan)

            val h = howlLeft.process(howlRngLeft.nextFloat())
            val h2 = howlRight.process(howlRngRight.nextFloat())
            val w = whistleLeft.process(whistleRngLeft.nextFloat()) * whistleLevel
            val w2 = whistleRight.process(whistleRngRight.nextFloat()) * whistleLevel

            // The buffet is added after the pan: below ~100 Hz the ear cannot
            // localise anyway, and panning it would only unbalance the
            // channels as the image drifts.
            // Squared *and capped*: brown noise already has a crest factor
            // around 4, and an uncapped square of a 1.8 gust would put the
            // strongest buffet peaks past full scale on their own.
            val gustSquared = (gust * gust).coerceAtMost(1.6f)
            val buffet = gustSquared * preset.buffetLevel
            val bl = buffetLowPassLeft.process(buffetLeft.next()) * buffet
            val br = buffetLowPassRight.process(buffetRight.next()) * buffet

            left[i] = ((h + w) * gust * panL + bl) * gain
            right[i] = ((h2 + w2) * gust * panR + br) * gain
        }
    }

    override fun reset(seed: Long) {
        rng.reseed(streamSeed(seed, 51))
        howlRngLeft.reseed(streamSeed(seed, 52))
        howlRngRight.reseed(streamSeed(seed, 53))
        whistleRngLeft.reseed(streamSeed(seed, 54))
        whistleRngRight.reseed(streamSeed(seed, 55))
        howlLeft.reset()
        howlRight.reset()
        whistleLeft.reset()
        whistleRight.reset()
        howlWalk.reset()
        whistleWalk.reset()
        buffetRngLeft.reseed(streamSeed(seed, 56))
        buffetRngRight.reseed(streamSeed(seed, 57))
        buffetLeft.reset()
        buffetRight.reset()
        buffetLowPassLeft.reset()
        buffetLowPassRight.reset()
        gustNoise.reset()
        panNoise.reset()
        swellClock.reset()
        swellSamplesLeft = 0
        controlCountdown = 0
        retune()
    }

    private fun startSwell() {
        swellLength =
            (rng.nextRange(preset.swellMinSeconds, preset.swellMaxSeconds) * sampleRate).toInt()
        swellSamplesLeft = swellLength
        swellAmplitude = rng.nextRange(0.3f, 0.9f)
    }

    private fun retune() {
        val howl = howlWalk.next()
        val whistle = whistleWalk.next()
        howlLeft.setBandPass(sampleRate, howl, preset.howlQ)
        howlRight.setBandPass(sampleRate, howl, preset.howlQ)
        whistleLeft.setBandPass(sampleRate, whistle, preset.whistleQ)
        whistleRight.setBandPass(sampleRate, whistle, preset.whistleQ)
    }

    private companion object {
        const val CONTROL_PERIOD = 64
        const val PAN_RATE_HZ = 0.05f
        /** Baseline gust level; the wander and swells ride on top of it. */
        const val GUST_BASE = 0.5f
    }
}
