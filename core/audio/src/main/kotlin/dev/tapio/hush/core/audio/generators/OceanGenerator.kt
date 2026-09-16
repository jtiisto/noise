package dev.tapio.hush.core.audio.generators

import dev.tapio.hush.core.audio.SoundGenerator
import dev.tapio.hush.core.audio.dsp.Biquad
import dev.tapio.hush.core.audio.dsp.BrownNoiseSource
import dev.tapio.hush.core.audio.dsp.NoiseRng
import dev.tapio.hush.core.audio.dsp.OnePoleLowPass
import dev.tapio.hush.core.audio.dsp.PinkFilter
import kotlin.math.PI
import kotlin.math.cos

/**
 * @param attackFraction share of a wave spent rising. Real surf rises faster
 *        than it falls (the break is quick, the drain is slow); 2/5 is the
 *        spec's value and the asymmetry is the main thing that stops the
 *        swell sounding like a tremolo pedal.
 * @param envelopeFloor level between waves. Never zero: the sea does not go
 *        silent, and a true zero would make the sound pump.
 * @param foamDelaySeconds how far the hiss lags the body. The crest is heard
 *        as water *first*, then as spray, and 0.4 s of lag is what makes a
 *        wave sound like it breaks rather than just gets louder.
 */
data class OceanPreset(
    val minPeriodSeconds: Float = 9f,
    val maxPeriodSeconds: Float = 15f,
    val attackFraction: Float = 0.4f,
    val envelopeFloor: Float = 0.07f,
    val foamDelaySeconds: Float = 0.4f,
    val foamLevel: Float = 0.60f,
    val foamLowHz: Float = 1_000f,
    val foamHighHz: Float = 3_000f,
    val washLevel: Float = 0.10f,
    val bodyLowPassMinHz: Float = 600f,
    val bodyLowPassMaxHz: Float = 4_000f,
    val pinkInBody: Float = 0.40f,
    val outputGain: Float = 0.135f,
) {
    companion object {
        val DEFAULT = OceanPreset()
    }
}

/**
 * Ocean surf: one slow swell envelope driving three spectrally distinct
 * layers.
 *
 * The whole sound is the envelope. A wave is 9-15 s long, rises over 2/5 of
 * that and drains over 3/5, and every audible property follows it:
 *
 *  * **Body** — brown noise with a little pink for mid presence, through a
 *    one-pole low-pass whose cutoff sweeps 600 Hz -> 4 kHz with the envelope.
 *    Loud water is *brighter*, not just louder; a fixed-timbre swell is the
 *    tell-tale sign of an amplitude-modulated noise bed.
 *  * **Foam** — white through a 1-3 kHz band-pass, driven by the envelope
 *    delayed 0.4 s and *squared*. Squaring narrows the hiss to the top of the
 *    wave so it reads as the crest breaking rather than as constant spray.
 *  * **Wash** — a quiet, constant low band: the rest of the beach, which does
 *    not stop between waves.
 *
 * The period is redrawn for every wave, so the sound never settles into a
 * rhythm the listener can predict — which is what would otherwise reveal it as
 * a loop after a few minutes.
 *
 * Left and right take independent noise streams under the shared envelope: one
 * wave, two ears.
 */
class OceanGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: OceanPreset = OceanPreset.DEFAULT,
) : SoundGenerator {

    private val rng = NoiseRng(streamSeed(seed, 41))
    private val bodyRngLeft = NoiseRng(streamSeed(seed, 42))
    private val bodyRngRight = NoiseRng(streamSeed(seed, 43))
    private val foamRngLeft = NoiseRng(streamSeed(seed, 44))
    private val foamRngRight = NoiseRng(streamSeed(seed, 45))
    private val washRngLeft = NoiseRng(streamSeed(seed, 46))
    private val washRngRight = NoiseRng(streamSeed(seed, 47))

    // 40 Hz rather than the source's default 25: below that a phone speaker
    // produces nothing but excursion, and the swell's weight comes from the
    // 60-200 Hz region anyway.
    private val brownLeft = BrownNoiseSource(sampleRate, bodyRngLeft, 10f, 40f)
    private val brownRight = BrownNoiseSource(sampleRate, bodyRngRight, 10f, 40f)
    private val pinkLeft = PinkFilter()
    private val pinkRight = PinkFilter()
    // Second order, not one pole: a 6 dB/oct roll-off from 4 kHz still leaves
    // the body's pink component clearly audible at 15 kHz, so crests came out
    // hissy above the 1-3 kHz band the foam layer is supposed to own.
    private val bodyLowPassLeft = Biquad()
    private val bodyLowPassRight = Biquad()

    private val foamHighPassLeft = Biquad()
    private val foamHighPassRight = Biquad()
    private val foamLowPassLeft = Biquad()
    private val foamLowPassRight = Biquad()

    private val washLeft = BrownNoiseSource(sampleRate, washRngLeft, 12f, 45f)
    private val washRight = BrownNoiseSource(sampleRate, washRngRight, 12f, 45f)
    private val washLowPassLeft = OnePoleLowPass(sampleRate, WASH_CUTOFF_HZ)
    private val washLowPassRight = OnePoleLowPass(sampleRate, WASH_CUTOFF_HZ)

    /** Ring buffer holding the last 0.4 s of envelope values for the foam layer. */
    private val envelopeDelay = FloatArray((preset.foamDelaySeconds * sampleRate).toInt() + 1)
    private var delayWrite = 0

    private var wavePhase = 0f
    private var wavePhaseIncrement = 0f
    private var controlCountdown = 0

    init {
        bodyLowPassLeft.setLowPass(sampleRate, preset.bodyLowPassMinHz, BODY_Q)
        bodyLowPassRight.setLowPass(sampleRate, preset.bodyLowPassMinHz, BODY_Q)
        configureFoam()
        startNewWave()
        // Start mid-wave rather than at a trough: the first thing a listener
        // hears when they tap "Ocean" should be water, not four seconds of
        // near silence.
        wavePhase = 0.25f
    }

    private fun configureFoam() {
        foamHighPassLeft.setHighPass(sampleRate, preset.foamLowHz, FOAM_Q)
        foamHighPassRight.setHighPass(sampleRate, preset.foamLowHz, FOAM_Q)
        foamLowPassLeft.setLowPass(sampleRate, preset.foamHighHz, FOAM_Q)
        foamLowPassRight.setLowPass(sampleRate, preset.foamHighHz, FOAM_Q)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain
        val floor = preset.envelopeFloor
        val pinkMix = preset.pinkInBody
        val foamLevel = preset.foamLevel
        val washLevel = preset.washLevel
        val delaySize = envelopeDelay.size

        for (i in 0 until frames) {
            val shape = waveShape(wavePhase)
            wavePhase += wavePhaseIncrement
            if (wavePhase >= 1f) {
                wavePhase -= 1f
                startNewWave()
            }
            val envelope = floor + (1f - floor) * shape

            // Control-rate retune of the body low-pass: an exp() per sample
            // would cost more than the whole rest of this generator, and the
            // cutoff moves by a fraction of a hertz per millisecond anyway.
            if (--controlCountdown <= 0) {
                controlCountdown = CONTROL_PERIOD
                val cutoff = preset.bodyLowPassMinHz +
                    (preset.bodyLowPassMaxHz - preset.bodyLowPassMinHz) * shape
                bodyLowPassLeft.setLowPass(sampleRate, cutoff, BODY_Q)
                bodyLowPassRight.setLowPass(sampleRate, cutoff, BODY_Q)
            }

            // Foam follows the envelope delayed and squared: the crest hiss.
            val delayed = envelopeDelay[delayWrite]
            envelopeDelay[delayWrite] = shape
            delayWrite++
            if (delayWrite >= delaySize) delayWrite = 0
            val foamEnvelope = delayed * delayed * foamLevel

            var l = bodyLowPassLeft.process(
                brownLeft.next() + pinkLeft.process(bodyRngLeft.nextFloat()) * pinkMix,
            ) * envelope
            var r = bodyLowPassRight.process(
                brownRight.next() + pinkRight.process(bodyRngRight.nextFloat()) * pinkMix,
            ) * envelope

            l += foamLowPassLeft.process(foamHighPassLeft.process(foamRngLeft.nextGaussian())) *
                foamEnvelope
            r += foamLowPassRight.process(foamHighPassRight.process(foamRngRight.nextGaussian())) *
                foamEnvelope

            l += washLowPassLeft.process(washLeft.next()) * washLevel
            r += washLowPassRight.process(washRight.next()) * washLevel

            left[i] = l * gain
            right[i] = r * gain
        }
    }

    override fun reset(seed: Long) {
        rng.reseed(streamSeed(seed, 41))
        bodyRngLeft.reseed(streamSeed(seed, 42))
        bodyRngRight.reseed(streamSeed(seed, 43))
        foamRngLeft.reseed(streamSeed(seed, 44))
        foamRngRight.reseed(streamSeed(seed, 45))
        washRngLeft.reseed(streamSeed(seed, 46))
        washRngRight.reseed(streamSeed(seed, 47))
        brownLeft.reset()
        brownRight.reset()
        pinkLeft.reset()
        pinkRight.reset()
        bodyLowPassLeft.reset()
        bodyLowPassRight.reset()
        foamHighPassLeft.reset()
        foamHighPassRight.reset()
        foamLowPassLeft.reset()
        foamLowPassRight.reset()
        washLeft.reset()
        washRight.reset()
        washLowPassLeft.reset()
        washLowPassRight.reset()
        envelopeDelay.fill(0f)
        delayWrite = 0
        controlCountdown = 0
        startNewWave()
        wavePhase = 0.25f
    }

    private fun startNewWave() {
        val period = rng.nextRange(preset.minPeriodSeconds, preset.maxPeriodSeconds)
        wavePhaseIncrement = 1f / (period * sampleRate)
    }

    /** Asymmetric raised cosine: 0 -> 1 over [attackFraction], 1 -> 0 over the rest. */
    private fun waveShape(phase: Float): Float {
        val a = preset.attackFraction
        return if (phase < a) {
            0.5f * (1f - cos(PI * phase / a).toFloat())
        } else {
            0.5f * (1f + cos(PI * (phase - a) / (1f - a)).toFloat())
        }
    }

    private companion object {
        const val BODY_Q = 0.707f
        const val FOAM_Q = 0.707f
        const val WASH_CUTOFF_HZ = 900f
        /** 64 samples = 1.3 ms at 48 kHz — far below any audible zipper rate. */
        const val CONTROL_PERIOD = 64
    }
}
