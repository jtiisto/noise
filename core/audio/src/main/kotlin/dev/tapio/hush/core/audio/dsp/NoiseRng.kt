package dev.tapio.hush.core.audio.dsp

import kotlin.math.ln

/**
 * xorshift128+ — the RNG every generator draws from.
 *
 * Why not [java.util.Random]: it is synchronized (a CAS per draw) and produces
 * doubles. Why not [kotlin.random.Random]: still a virtual call plus a
 * rejection loop for bounded ints. xorshift128+ is three shifts, three xors
 * and an add — a few nanoseconds — and its state is two longs, so a render
 * loop can keep it hot in cache. Quality is far beyond what noise synthesis
 * needs (BigCrush-clean apart from the lowest bit's linear complexity, and we
 * only ever use the top 24 bits).
 *
 * Not thread safe: each generator owns its own instance and generators only
 * ever run on the audio thread.
 */
class NoiseRng(seed: Long) {

    private var s0: Long = 0
    private var s1: Long = 0

    init {
        reseed(seed)
    }

    /** Re-derives the 128-bit state from [seed] via SplitMix64 (never all-zero). */
    fun reseed(seed: Long) {
        var z = splitMix64(seed)
        s0 = z
        z = splitMix64(z)
        s1 = z
        if (s0 == 0L && s1 == 0L) {
            s0 = GOLDEN_GAMMA
            s1 = -GOLDEN_GAMMA
        }
    }

    /** Raw 64-bit draw. */
    fun nextLong(): Long {
        var x = s0
        val y = s1
        s0 = y
        x = x xor (x shl 23)
        s1 = x xor y xor (x ushr 17) xor (y ushr 26)
        return s1 + y
    }

    /** Uniform in `[0, 1)`, 24-bit resolution (exactly a float mantissa). */
    fun nextUnit(): Float = (nextLong() ushr 40).toInt() * UNIT_SCALE

    /**
     * Uniform in `[-1, 1)` — the white-noise sample source.
     * RMS is `1/sqrt(3) ~= 0.5774`; generators fold that into their calibration.
     */
    fun nextFloat(): Float = (nextLong() ushr 40).toInt() * BIPOLAR_SCALE - 1f

    /** Uniform in `[min, max)`. */
    fun nextRange(min: Float, max: Float): Float = min + (max - min) * nextUnit()

    /** Uniform in `[0, bound)`; [bound] must be positive. The tiny modulo bias is irrelevant here. */
    fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()

    /** True with probability [p]. */
    fun nextBoolean(p: Float): Boolean = nextUnit() < p

    /**
     * Approximately Gaussian, unit variance, hard-bounded to +/-3 (sum of
     * three uniforms — the Irwin–Hall n=3 trick from Farnell). Used where the
     * *shape* of the amplitude distribution is audible: rain and ocean beds
     * sound thin with flat-topped uniform noise, because real dense-event
     * noise is Gaussian by the central limit theorem.
     */
    fun nextGaussian(): Float = nextFloat() + nextFloat() + nextFloat()

    /**
     * Exponentially distributed with rate 1 — the inter-arrival time of a
     * Poisson process (raindrops, crackles). Multiply by `sampleRate / rate`
     * to get a sample count.
     */
    fun nextExponential(): Float = -ln(1f - nextUnit())

    private companion object {
        const val GOLDEN_GAMMA: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val UNIT_SCALE: Float = 1f / (1 shl 24)
        const val BIPOLAR_SCALE: Float = 2f / (1 shl 24)

        fun splitMix64(state: Long): Long {
            var z = state + GOLDEN_GAMMA
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }
    }
}
