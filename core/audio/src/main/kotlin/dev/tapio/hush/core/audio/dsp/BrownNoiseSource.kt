package dev.tapio.hush.core.audio.dsp

import kotlin.math.sqrt

/**
 * Unit-RMS brown noise: a leaky integrator of white (-6 dB/oct) with the
 * sub-audio wander high-passed away.
 *
 * Half a dozen generators want "a low rumble" — rain's distant body, ocean's
 * swell, campfire's bed, thunder, the airplane cabin — and hand-tuning a
 * makeup gain in each of them would be five constants that all drift apart
 * when a cutoff changes. Instead the makeup is *derived*: for white input the
 * leaky integrator's RMS gain is `sqrt((1-r)/(1+r))`, and a one-pole
 * high-pass at `b` on a one-pole low-pass at `a` keeps a power fraction of
 * `a/(a+b)`. Both are exact for the continuous prototypes and accurate to
 * well under 0.1 dB at audio sample rates, so `next()` comes out at RMS 1.0
 * whatever the corners are set to and callers only ever apply a level.
 *
 * The output is Gaussian-ish with a crest factor around 4, so a caller
 * targeting -20 dBFS RMS should expect peaks near -8 dBFS.
 */
class BrownNoiseSource(
    sampleRate: Int,
    private val rng: NoiseRng,
    private val integratorHz: Float = 8f,
    private val highPassHz: Float = 25f,
) {

    private val integrator = LeakyIntegrator(sampleRate, integratorHz)
    private val highPass = OnePoleHighPass(sampleRate, highPassHz)
    private val makeup: Float

    init {
        val whiteRms = 1f / sqrt(3f) // uniform [-1,1)
        val highPassAmplitude = sqrt(integratorHz / (integratorHz + highPassHz))
        makeup = 1f / (whiteRms * integrator.whiteRmsGain * highPassAmplitude)
    }

    fun reset() {
        integrator.reset()
        highPass.reset()
    }

    fun next(): Float = highPass.process(integrator.process(rng.nextFloat())) * makeup
}
