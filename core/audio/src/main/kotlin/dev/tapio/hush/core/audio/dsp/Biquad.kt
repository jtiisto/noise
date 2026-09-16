package dev.tapio.hush.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RBJ-cookbook biquad in transposed direct form II.
 *
 * Why TDF-II: it needs two state words instead of four, and its rounding
 * behaviour is the friendliest of the direct forms when coefficients are
 * retuned while the filter is running — which we do constantly (wind centre
 * frequencies, stream resonators, per-voice crackle bands). State and
 * arithmetic are `Double`: on ARM64 and on the JVM a double multiply costs the
 * same as a float one, and the high-Q resonators (Q up to 12 at 400 Hz, pole
 * radius ~0.9997) would otherwise sit uncomfortably close to float precision.
 *
 * Every `set*` method is allocation-free and safe to call from the render
 * thread at control rate; none of them touch the state, so retuning does not
 * click as long as the change per update is small.
 */
class Biquad {

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var z1 = 0.0
    private var z2 = 0.0

    /** Clears the delay line but keeps the coefficients. */
    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(x: Float): Float {
        val input = x.toDouble()
        val y = b0 * input + z1
        z1 = b1 * input - a1 * y + z2
        z2 = b2 * input - a2 * y
        return y.toFloat()
    }

    /** Straight-through (used as a "bypass" state and by tests). */
    fun setIdentity() {
        b0 = 1.0
        b1 = 0.0
        b2 = 0.0
        a1 = 0.0
        a2 = 0.0
    }

    fun setLowPass(sampleRate: Int, freqHz: Float, q: Float) {
        val w = omega(sampleRate, freqHz)
        val cw = cos(w)
        val alpha = sin(w) / (2.0 * q.toDouble().coerceAtLeast(MIN_Q))
        val b0n = (1.0 - cw) / 2.0
        normalize(b0n, 1.0 - cw, b0n, 1.0 + alpha, -2.0 * cw, 1.0 - alpha)
    }

    fun setHighPass(sampleRate: Int, freqHz: Float, q: Float) {
        val w = omega(sampleRate, freqHz)
        val cw = cos(w)
        val alpha = sin(w) / (2.0 * q.toDouble().coerceAtLeast(MIN_Q))
        val b0n = (1.0 + cw) / 2.0
        normalize(b0n, -(1.0 + cw), b0n, 1.0 + alpha, -2.0 * cw, 1.0 - alpha)
    }

    /**
     * Constant-peak-gain band-pass (0 dB at the centre frequency), so raising
     * Q narrows the band without changing how loud the resonance is — the
     * behaviour we want when a stream resonator's Q is randomised per voice.
     */
    fun setBandPass(sampleRate: Int, freqHz: Float, q: Float) {
        val w = omega(sampleRate, freqHz)
        val cw = cos(w)
        val alpha = sin(w) / (2.0 * q.toDouble().coerceAtLeast(MIN_Q))
        normalize(alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * cw, 1.0 - alpha)
    }

    fun setNotch(sampleRate: Int, freqHz: Float, q: Float) {
        val w = omega(sampleRate, freqHz)
        val cw = cos(w)
        val alpha = sin(w) / (2.0 * q.toDouble().coerceAtLeast(MIN_Q))
        normalize(1.0, -2.0 * cw, 1.0, 1.0 + alpha, -2.0 * cw, 1.0 - alpha)
    }

    /** Peaking EQ: [gainDb] boost/cut at [freqHz] with bandwidth set by [q]. */
    fun setPeaking(sampleRate: Int, freqHz: Float, q: Float, gainDb: Float) {
        val a = dbToAmplitudeSqrt(gainDb)
        val w = omega(sampleRate, freqHz)
        val cw = cos(w)
        val alpha = sin(w) / (2.0 * q.toDouble().coerceAtLeast(MIN_Q))
        normalize(
            1.0 + alpha * a, -2.0 * cw, 1.0 - alpha * a,
            1.0 + alpha / a, -2.0 * cw, 1.0 - alpha / a,
        )
    }

    /** Low shelf, [gainDb] below [freqHz]. [slope] 1.0 is the maximally flat shelf. */
    fun setLowShelf(sampleRate: Int, freqHz: Float, gainDb: Float, slope: Float = 1f) {
        val a = dbToAmplitudeSqrt(gainDb)
        val w = omega(sampleRate, freqHz)
        val cw = cos(w)
        val alpha = shelfAlpha(w, a, slope)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        normalize(
            a * ((a + 1.0) - (a - 1.0) * cw + twoSqrtAAlpha),
            2.0 * a * ((a - 1.0) - (a + 1.0) * cw),
            a * ((a + 1.0) - (a - 1.0) * cw - twoSqrtAAlpha),
            (a + 1.0) + (a - 1.0) * cw + twoSqrtAAlpha,
            -2.0 * ((a - 1.0) + (a + 1.0) * cw),
            (a + 1.0) + (a - 1.0) * cw - twoSqrtAAlpha,
        )
    }

    /** High shelf, [gainDb] above [freqHz]. */
    fun setHighShelf(sampleRate: Int, freqHz: Float, gainDb: Float, slope: Float = 1f) {
        val a = dbToAmplitudeSqrt(gainDb)
        val w = omega(sampleRate, freqHz)
        val cw = cos(w)
        val alpha = shelfAlpha(w, a, slope)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        normalize(
            a * ((a + 1.0) + (a - 1.0) * cw + twoSqrtAAlpha),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * cw),
            a * ((a + 1.0) + (a - 1.0) * cw - twoSqrtAAlpha),
            (a + 1.0) - (a - 1.0) * cw + twoSqrtAAlpha,
            2.0 * ((a - 1.0) - (a + 1.0) * cw),
            (a + 1.0) - (a - 1.0) * cw - twoSqrtAAlpha,
        )
    }

    /**
     * Magnitude response at [freqHz], for tests and for offline normalisation.
     * Not used in the render path.
     */
    fun magnitudeAt(sampleRate: Int, freqHz: Float): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val cw1 = cos(w)
        val sw1 = sin(w)
        val cw2 = cos(2 * w)
        val sw2 = sin(2 * w)
        val numRe = b0 + b1 * cw1 + b2 * cw2
        val numIm = -(b1 * sw1 + b2 * sw2)
        val denRe = 1.0 + a1 * cw1 + a2 * cw2
        val denIm = -(a1 * sw1 + a2 * sw2)
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        return if (den == 0.0) 0.0 else num / den
    }

    private fun normalize(
        nb0: Double,
        nb1: Double,
        nb2: Double,
        na0: Double,
        na1: Double,
        na2: Double,
    ) {
        val inv = 1.0 / na0
        b0 = nb0 * inv
        b1 = nb1 * inv
        b2 = nb2 * inv
        a1 = na1 * inv
        a2 = na2 * inv
    }

    private companion object {
        const val MIN_Q = 0.05
        const val LN10_OVER_40 = 0.05756462732485114 // ln(10) / 40

        fun omega(sampleRate: Int, freqHz: Float): Double {
            // Keep the pre-warped frequency inside the unit circle: above
            // ~0.45 * fs the cookbook formulas degenerate.
            val f = freqHz.toDouble().coerceIn(1.0, sampleRate * 0.45)
            return 2.0 * PI * f / sampleRate
        }

        /** `10^(dB/40)` = sqrt of the amplitude gain, which is what RBJ calls A. */
        fun dbToAmplitudeSqrt(db: Float): Double = exp(db.toDouble() * LN10_OVER_40)

        fun shelfAlpha(w: Double, a: Double, slope: Float): Double {
            val s = slope.toDouble().coerceIn(0.1, 2.0)
            val inner = (a + 1.0 / a) * (1.0 / s - 1.0) + 2.0
            return sin(w) / 2.0 * sqrt(inner.coerceAtLeast(0.0))
        }
    }
}
