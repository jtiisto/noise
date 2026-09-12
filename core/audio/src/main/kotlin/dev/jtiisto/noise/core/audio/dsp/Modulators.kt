package dev.jtiisto.noise.core.audio.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * Wavetable sine, 4096 points plus linear interpolation.
 *
 * Crickets need four carriers around 4 kHz and every generator wants a slow
 * LFO or two; `Math.sin` at 48 kHz x N voices is measurable on a low-end
 * phone, while a 16 KB table read plus a lerp is not. THD of the interpolated
 * table is about -78 dB, far below the noise beds it modulates.
 */
object FastSine {

    private const val BITS = 12
    private const val SIZE = 1 shl BITS
    private val TABLE = FloatArray(SIZE + 1) { sin(2.0 * PI * it / SIZE).toFloat() }

    /** Sine of a normalised phase, where 1.0 is a full turn. Phase may be any finite value. */
    fun at(phase01: Float): Float {
        var p = phase01 - phase01.toInt()
        if (p < 0f) p += 1f
        val x = p * SIZE
        val i = x.toInt()
        val frac = x - i
        val a = TABLE[i]
        return a + (TABLE[i + 1] - a) * frac
    }
}

/** Free-running sine LFO with a normalised phase accumulator. */
class SineLfo(private val sampleRate: Int, frequencyHz: Float = 1f, phase01: Float = 0f) {

    private var phase = phase01
    private var increment = 0f

    init {
        setFrequency(frequencyHz)
    }

    fun setFrequency(hz: Float) {
        increment = hz / sampleRate
    }

    fun setPhase(phase01: Float) {
        phase = phase01
    }

    fun reset() {
        phase = 0f
    }

    /** Advances one sample and returns the value in `[-1, 1]`. */
    fun next(): Float {
        val v = FastSine.at(phase)
        phase += increment
        if (phase >= 1f) phase -= 1f
        return v
    }
}

/**
 * Band-limited noise used as a control signal: white noise through a one-pole
 * LP, scaled back to roughly unit RMS and clamped to `[-1, 1]`.
 *
 * This is the "random walk" the spec asks for in wind gusts, rain-gust
 * amplitude and stream wobble. A true unbounded walk would need a separate
 * bound-and-reflect step; filtered noise gives the same audible wandering with
 * a stationary distribution, which means a gust never wanders off and stays
 * loud for a minute.
 */
class SmoothNoise(
    sampleRate: Int,
    private val rng: NoiseRng,
    rateHz: Float = 0.1f,
) {

    private val lp = OnePoleLowPass(sampleRate, rateHz)
    private var normalisation = 1f
    private var current = 0f

    init {
        setRate(rateHz)
    }

    fun setRate(hz: Float) {
        lp.setCutoff(hz)
        // A one-pole LP of white noise has RMS = sqrt(a / (2 - a)); undo it so
        // the control signal keeps the same swing whatever rate we pick.
        normalisation = 1f / OnePoleLowPass.whiteRmsGain(lp.coefficient).coerceAtLeast(1e-6f)
    }

    fun reset() {
        lp.reset()
        current = 0f
    }

    /** Next value in `[-1, 1]` (clamped; the raw signal is Gaussian so clipping is rare). */
    fun next(): Float {
        val raw = lp.process(rng.nextFloat()) * normalisation * UNIT_RMS_TO_PEAK
        current = raw.coerceIn(-1f, 1f)
        return current
    }

    /** Last value produced, without advancing. */
    val value: Float get() = current

    private companion object {
        // Filtered uniform noise is near-Gaussian; 0.5 puts +/-2 sigma inside
        // the clamp so the control signal wanders freely but almost never
        // flat-tops.
        const val UNIT_RMS_TO_PEAK = 0.5f
    }
}

/**
 * A value that wanders inside `[min, max]`: a fresh uniform target is drawn
 * every `1 / rateHz` seconds and the output slews toward it with a one-pole.
 *
 * Used where the *range* matters and must be respected exactly — wind's
 * 250-700 Hz centre frequency, a stream resonator's +/-8 % detune. Unlike
 * [SmoothNoise] the bound is hard, so a filter never gets tuned outside its
 * design range.
 */
class RandomWalk(
    private val sampleRate: Int,
    private val rng: NoiseRng,
    private var min: Float,
    private var max: Float,
    rateHz: Float = 0.1f,
) {

    private val lp = OnePoleLowPass(sampleRate, rateHz)
    private var target = 0f
    private var samplesToNextTarget = 0
    private var period = 1

    init {
        setRate(rateHz)
        target = rng.nextRange(min, max)
        // Start settled on the first target so the opening seconds are not a
        // slow slew up from zero (which would detune every filter we drive).
        lp.forceTo(target)
    }

    fun setRange(min: Float, max: Float) {
        this.min = min
        this.max = max
    }

    fun setRate(hz: Float) {
        period = (sampleRate / hz.coerceAtLeast(0.001f)).toInt().coerceAtLeast(1)
        // Slew about three times faster than the redraw so the output actually
        // reaches the neighbourhood of each target before the next one lands.
        lp.setCutoff(hz * 3f)
        samplesToNextTarget = 0
    }

    fun reset() {
        target = rng.nextRange(min, max)
        lp.forceTo(target)
        samplesToNextTarget = 0
    }

    fun next(): Float {
        if (samplesToNextTarget <= 0) {
            target = rng.nextRange(min, max)
            samplesToNextTarget = period
        }
        samplesToNextTarget--
        return lp.process(target).coerceIn(minOf(min, max), maxOf(min, max))
    }

    val value: Float get() = lp.value
}
