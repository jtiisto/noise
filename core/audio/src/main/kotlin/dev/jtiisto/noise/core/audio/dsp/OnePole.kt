package dev.jtiisto.noise.core.audio.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * One-pole low-pass, `y[n] = y[n-1] + a * (x[n] - y[n-1])`.
 *
 * The workhorse of this engine: -6 dB/oct with no resonance, one multiply and
 * two adds, unconditionally stable however fast you sweep it. Anywhere the
 * spec calls for "a gentle LP" this is what it means; biquads are reserved for
 * places where a slope steeper than 6 dB/oct or a resonance is actually
 * audible.
 */
class OnePoleLowPass(private val sampleRate: Int, cutoffHz: Float = 1_000f) {

    private var a = 0f
    private var y = 0f

    /** Filter coefficient in `(0, 1]`; exposed so callers can compensate gain. */
    val coefficient: Float get() = a

    init {
        setCutoff(cutoffHz)
    }

    fun setCutoff(hz: Float) {
        // Exact impulse-invariant pole: a = 1 - e^(-2*pi*f/fs). The naive
        // "a = 2*pi*f/fs" approximation is 10 % off by 3 kHz at 48 kHz and
        // would quietly detune the bright bands (rain drops, fire hiss).
        val f = hz.coerceIn(0.01f, sampleRate * 0.49f)
        a = (1.0 - exp(-2.0 * PI * f / sampleRate)).toFloat()
    }

    fun reset() {
        y = 0f
    }

    /** Forces the state, so a control-rate filter can start already settled. */
    fun forceTo(value: Float) {
        y = value
    }

    fun process(x: Float): Float {
        y += a * (x - y)
        return y
    }

    /** Current output without advancing the filter. */
    val value: Float get() = y

    companion object {
        /**
         * How much a one-pole LP with coefficient [a] attenuates the RMS of a
         * white input: the output variance is `a / (2 - a)` times the input's.
         * Generators divide by this to keep their level independent of cutoff,
         * which is what makes per-voice random cutoffs (raindrops) not also
         * randomise loudness.
         */
        fun whiteRmsGain(a: Float): Float = sqrt(a / (2f - a))
    }
}

/**
 * One-pole high-pass built as `x - lowpass(x)`. +6 dB/oct below the corner,
 * flat above. Used to strip DC and sub-audio rumble from integrated noise.
 */
class OnePoleHighPass(sampleRate: Int, cutoffHz: Float = 20f) {

    private val lp = OnePoleLowPass(sampleRate, cutoffHz)

    fun setCutoff(hz: Float) = lp.setCutoff(hz)

    fun reset() = lp.reset()

    fun process(x: Float): Float = x - lp.process(x)
}

/**
 * Leaky integrator, `y[n] = r * y[n-1] + (1 - r) * x[n]`.
 *
 * Algebraically the same filter as [OnePoleLowPass] — it is named separately
 * because that is how the spec describes brown-noise generation, and because
 * the mental model differs: here the point is that white noise *accumulates*
 * (a random walk, hence -6 dB/oct) and the leak is only there to stop the walk
 * from drifting to infinity. The corner frequency is the leak rate.
 */
class LeakyIntegrator(private val sampleRate: Int, cutoffHz: Float = 8f) {

    private var r = 0f
    private var y = 0f

    init {
        setCutoff(cutoffHz)
    }

    fun setCutoff(hz: Float) {
        val f = hz.coerceIn(0.01f, sampleRate * 0.49f)
        r = exp(-2.0 * PI * f / sampleRate).toFloat()
    }

    fun reset() {
        y = 0f
    }

    fun process(x: Float): Float {
        y = r * y + (1f - r) * x
        return y
    }

    /**
     * RMS gain applied to a white input — the reciprocal is the makeup gain
     * brown noise needs (at 8 Hz on 48 kHz it is about -35 dB, so the makeup
     * is large and worth computing rather than guessing).
     */
    val whiteRmsGain: Float get() = sqrt((1f - r) / (1f + r))
}
