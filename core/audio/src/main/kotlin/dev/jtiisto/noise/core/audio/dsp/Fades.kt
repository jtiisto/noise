package dev.jtiisto.noise.core.audio.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * The equal-power fade curve, `g(t) = sin(t * pi/2)^2`, as a lookup table.
 *
 * Why sin-squared rather than a linear ramp: a linear fade of a noise bed
 * sounds like it hangs at the top and then drops off a cliff, because loudness
 * follows power, not amplitude. `sin^2` and its mirror `cos^2` sum to 1 in
 * *amplitude*, which for two uncorrelated noise sources (our layer crossfades)
 * keeps the perceived loudness of the pair almost constant across the fade.
 *
 * A 4096-point table with linear interpolation is accurate to ~1e-7, which is
 * far below the 24-bit floor, and costs one array read where `sin` would cost
 * tens of nanoseconds per sample per fading layer.
 */
object EqualPowerFade {

    private const val BITS = 12
    private const val SIZE = 1 shl BITS
    private val TABLE = FloatArray(SIZE + 1) {
        val s = sin(it.toDouble() / SIZE * PI / 2.0)
        (s * s).toFloat()
    }

    /**
     * Gain for a fade position [t] in `[0, 1]`. Exactly `0f` at 0 and exactly
     * `1f` at 1 — the endpoints are special-cased rather than trusted to
     * floating point, because "the fade envelope reaches exactly 0 and 1" is
     * what lets the renderer decide a fade is finished.
     */
    fun value(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val x = t * SIZE
        val i = x.toInt()
        val frac = x - i
        val a = TABLE[i]
        return a + (TABLE[i + 1] - a) * frac
    }
}

/**
 * Helpers for the difference-of-exponentials envelope
 * `e^(-t/decay) - e^(-t/attack)` used by every event voice in the engine.
 *
 * It is the cheapest envelope that has a real attack (two multiplies per
 * sample, no branch, no state machine), and unlike a linear attack it never
 * produces the corner that makes a short burst click.
 */
object DualExponential {

    /**
     * Peak value of `e^(-t/decay) - e^(-t/attack)` for `ratio = decay/attack`.
     * Callers divide by this to normalise a voice to a known peak. Uses two
     * `pow` calls, so call it per *event*, never per sample.
     */
    fun peak(ratio: Float): Float {
        val r = ratio.toDouble().coerceAtLeast(1.0001)
        val e = 1.0 / (r - 1.0)
        return (Math.pow(r, -e) - Math.pow(r, -r * e)).toFloat()
    }

    /** Per-sample decay multiplier that reaches -40 dB after [samples] samples. */
    fun decayCoefficient(samples: Float): Float =
        kotlin.math.exp(-4.605170186 / samples.toDouble().coerceAtLeast(1.0)).toFloat()
}

/**
 * A parameter that can only move toward its target at a bounded rate, one
 * sample at a time. Every gain in the engine goes through one of these: a
 * gain that jumps by even 0.01 in a single sample is an audible tick, and a
 * mix app changes gains constantly.
 */
class LinearRamp(initial: Float = 0f) {

    var value: Float = initial
        private set

    var target: Float = initial
        private set

    /** Signed per-sample increment. */
    private var step: Float = 0f

    /** Samples still to go. Counting down (rather than comparing against the
     * target) is what makes the ramp land on the target *exactly* on the
     * requested sample: accumulating 100 additions of 0.01f in single
     * precision arrives at 0.99999994, and a ramp that never quite finishes
     * would keep the renderer on its slow path forever. */
    private var remaining: Int = 0

    val isAtTarget: Boolean get() = value == target && remaining == 0

    /** Sets a new target, reached in exactly [samples] samples (minimum 1). */
    fun rampTo(target: Float, samples: Int) {
        this.target = target
        if (value == target) {
            remaining = 0
            step = 0f
            return
        }
        remaining = if (samples < 1) 1 else samples
        step = (target - value) / remaining
    }

    /** Discontinuous set — only legitimate before the first render or on reset. */
    fun jumpTo(value: Float) {
        this.value = value
        this.target = value
        this.step = 0f
        this.remaining = 0
    }

    /** Advances one sample and returns the new value. */
    fun next(): Float {
        if (remaining <= 0) return value
        remaining--
        value = if (remaining == 0) target else value + step
        return value
    }
}

/**
 * The output stage's soft clipper, `f(x) = x * (27 + x^2) / (27 + 9 x^2)`.
 *
 * Unity slope at the origin (so nothing changes at normal listening levels),
 * reaches exactly +/-1 at x = +/-3, and is smooth in between: three layers all
 * pushed to the top can only compress, never produce the buzz of a hard clip.
 * The input is clamped to +/-3 first because the rational function turns back
 * on itself beyond that point.
 */
object SoftClipper {

    fun clip(x: Float): Float {
        val v = if (x > 3f) 3f else if (x < -3f) -3f else x
        val v2 = v * v
        val y = v * (27f + v2) / (27f + 9f * v2)
        // The rational form reaches exactly +/-1 at +/-3, so single-precision
        // rounding can land a hair outside near the knee. One clamp guarantees
        // the invariant every downstream consumer relies on.
        return if (y > 1f) 1f else if (y < -1f) -1f else y
    }
}
