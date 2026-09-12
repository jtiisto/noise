package dev.jtiisto.noise.core.audio.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Makeup gain that restores white noise to unit RMS after a constant-peak-gain
 * band-pass.
 *
 * The filter's noise power gain is its equivalent noise bandwidth over the
 * Nyquist band: `(pi/2) * (f0/Q) / (fs/2)`, i.e. `pi * f0 / (Q * fs)`. Without
 * this, randomising a crackle's pitch or a stream resonator's Q would also
 * randomise its loudness by up to 8 dB, and the whole bank would need
 * hand-tuning every time a range changed.
 */
fun bandPassNoiseMakeup(centreHz: Float, q: Float, sampleRate: Int): Float {
    val f0 = centreHz.coerceIn(1f, sampleRate * 0.49f)
    val power = (PI * f0 / (q.coerceAtLeast(0.1f) * sampleRate)).coerceIn(1e-6, 1.0)
    return (1.0 / sqrt(power)).toFloat()
}

/**
 * Fixed-size pool of short percussive "event" voices — raindrops, fire
 * crackles, anything Poisson-scheduled.
 *
 * Design notes:
 *  * **Struct of arrays.** Voice state lives in parallel primitive arrays
 *    allocated once. An `Array<Voice>` of objects would also avoid allocation
 *    at render time, but 24 separate objects scatter across the heap; the SoA
 *    layout keeps a whole pool inside a couple of cache lines.
 *  * **Free stack + active list.** Only *live* voices are visited per sample.
 *    Rain at 60 drops/s with ~20 ms tails averages barely more than one live
 *    voice, so the inner loop usually does nothing — that is the difference
 *    between rain costing 0.3 % and 3 % of a core.
 *  * **Voice stealing** takes the quietest live voice, which for decaying
 *    bursts is the oldest-and-faded one; stealing it is inaudible.
 *  * **Difference-of-exponentials envelope.** `e^(-t/tau) - e^(-t/(tau/8))`
 *    starts at exactly zero, rises in a few hundred microseconds and decays
 *    exponentially. A one-sample attack would click; this costs two multiplies
 *    per sample and no branch. Because the attack/decay ratio is fixed at 8,
 *    the peak of the difference is the constant 0.6498, so the normalisation
 *    is a compile-time constant instead of a `pow` per spawn.
 */
abstract class EventVoicePool(val capacity: Int) {

    protected val env1 = FloatArray(capacity)
    protected val env2 = FloatArray(capacity)
    protected val decay1 = FloatArray(capacity)
    protected val decay2 = FloatArray(capacity)
    protected val amplitude = FloatArray(capacity)
    protected val panLeft = FloatArray(capacity)
    protected val panRight = FloatArray(capacity)

    protected val activeIndex = IntArray(capacity)
    private val freeStack = IntArray(capacity) { it }
    private var freeTop = capacity

    /** Number of voices currently sounding. */
    var activeCount: Int = 0
        protected set

    /** Total voices started since the last [reset] — the tests' drop-rate probe. */
    var spawnCount: Long = 0L
        private set

    fun reset() {
        activeCount = 0
        freeTop = capacity
        spawnCount = 0L
        for (i in 0 until capacity) {
            freeStack[i] = i
            env1[i] = 0f
            env2[i] = 0f
            amplitude[i] = 0f
        }
        onReset()
    }

    protected open fun onReset() = Unit

    /**
     * Claims a slot, stealing the quietest live voice when the pool is full,
     * and installs the shared envelope/pan state.
     *
     * @param durationSamples time to roughly -40 dB; the voice is released a
     *        little after that.
     * @param pan -1 hard left .. +1 hard right (equal-power).
     */
    protected fun startVoice(durationSamples: Int, peakAmplitude: Float, pan: Float): Int {
        val slot: Int
        if (freeTop > 0) {
            slot = freeStack[--freeTop]
            activeIndex[activeCount++] = slot
        } else {
            var quietest = 0
            var quietestEnergy = Float.MAX_VALUE
            for (j in 0 until activeCount) {
                val v = activeIndex[j]
                val e = amplitude[v] * env1[v]
                if (e < quietestEnergy) {
                    quietestEnergy = e
                    quietest = j
                }
            }
            slot = activeIndex[quietest]
        }
        val n = if (durationSamples < 2) 2 else durationSamples
        // exp(-DECAY_SPAN / n) puts the envelope at -40 dB after n samples.
        decay1[slot] = exp(-DECAY_SPAN / n).toFloat()
        decay2[slot] = exp(-DECAY_SPAN * ATTACK_RATIO / n).toFloat()
        env1[slot] = 1f
        env2[slot] = 1f
        amplitude[slot] = peakAmplitude * PEAK_NORMALISATION
        val p = pan.coerceIn(-1f, 1f)
        // Equal-power pan: cos/sin of the quarter-turn, approximated with the
        // exact sqrt form so a centre-panned event is -3 dB per side.
        panLeft[slot] = sqrt((1f - p) * 0.5f)
        panRight[slot] = sqrt((1f + p) * 0.5f)
        spawnCount++
        return slot
    }

    /** Swap-removes the active-list entry at [listIndex] and frees its slot. */
    protected fun releaseAt(listIndex: Int) {
        val slot = activeIndex[listIndex]
        activeCount--
        activeIndex[listIndex] = activeIndex[activeCount]
        freeStack[freeTop++] = slot
        env1[slot] = 0f
        env2[slot] = 0f
    }

    protected companion object {
        /** ln(100) — an envelope of exp(-DECAY_SPAN * t/n) is at -40 dB when t = n. */
        const val DECAY_SPAN = 4.605170186
        const val ATTACK_RATIO = 8.0
        /** Peak of `e^-x - e^-8x`, reached at x = ln(8)/7. */
        const val PEAK_NORMALISATION = 1.53886f
        /** Below this the voice contributes less than a 16-bit LSB. */
        const val RELEASE_THRESHOLD = 1e-3f
    }
}

/**
 * Voices that are bursts of white noise through a per-voice one-pole
 * low-pass. This is the classic raindrop: a spectrally random tick whose
 * brightness says how close and how big the drop was.
 *
 * The output is level-compensated for the low-pass, so choosing a dark cutoff
 * makes a drop *duller*, not *quieter* — otherwise the drop-size randomisation
 * would double as an unwanted amplitude randomisation.
 */
class NoiseBurstVoicePool(
    capacity: Int,
    private val rng: NoiseRng,
    private val sampleRate: Int,
) : EventVoicePool(capacity) {

    private val lpCoefficient = FloatArray(capacity)
    private val lpState = FloatArray(capacity)

    override fun onReset() {
        for (i in 0 until capacity) lpState[i] = 0f
    }

    /** @param cutoffHz one-pole colour of this burst. */
    fun spawn(durationSamples: Int, peakAmplitude: Float, pan: Float, cutoffHz: Float) {
        val slot = startVoice(durationSamples, peakAmplitude, pan)
        val f = cutoffHz.coerceIn(20f, sampleRate * 0.49f)
        val a = (1.0 - exp(-2.0 * Math.PI * f / sampleRate)).toFloat()
        lpCoefficient[slot] = a
        lpState[slot] = 0f
        // Undo the RMS loss of the low-pass so cutoff controls colour only.
        amplitude[slot] = amplitude[slot] / OnePoleLowPass.whiteRmsGain(a).coerceAtLeast(1e-4f)
    }

    /** Accumulates one sample of every live voice into [left]/[right] at [index]. */
    fun addSample(left: FloatArray, right: FloatArray, index: Int) {
        var j = 0
        var l = left[index]
        var r = right[index]
        while (j < activeCount) {
            val v = activeIndex[j]
            var e1 = env1[v]
            val e2 = env2[v]
            var s = lpState[v]
            s += lpCoefficient[v] * (rng.nextFloat() - s)
            lpState[v] = s
            val out = s * (e1 - e2) * amplitude[v]
            l += out * panLeft[v]
            r += out * panRight[v]
            e1 *= decay1[v]
            env1[v] = e1
            env2[v] = e2 * decay2[v]
            if (e1 < RELEASE_THRESHOLD) releaseAt(j) else j++
        }
        left[index] = l
        right[index] = r
    }
}

/**
 * Voices that are bursts of white noise through a per-voice resonant
 * band-pass. Fire crackles are the use case: a crackle is a tiny steam
 * explosion whose pitch is set by the cavity that popped, so a resonance
 * (Q around 6-14) reads as "wood", where a plain low-passed tick reads as
 * "static".
 */
class ResonantBurstVoicePool(
    capacity: Int,
    private val rng: NoiseRng,
    private val sampleRate: Int,
) : EventVoicePool(capacity) {

    private val filters = Array(capacity) { Biquad() }

    override fun onReset() {
        for (i in 0 until capacity) filters[i].reset()
    }

    fun spawn(durationSamples: Int, peakAmplitude: Float, pan: Float, centreHz: Float, q: Float) {
        val slot = startVoice(durationSamples, peakAmplitude, pan)
        val filter = filters[slot]
        filter.reset()
        filter.setBandPass(sampleRate, centreHz, q)
        amplitude[slot] = amplitude[slot] * bandPassNoiseMakeup(centreHz, q, sampleRate)
    }

    fun addSample(left: FloatArray, right: FloatArray, index: Int) {
        var j = 0
        var l = left[index]
        var r = right[index]
        while (j < activeCount) {
            val v = activeIndex[j]
            var e1 = env1[v]
            val e2 = env2[v]
            val out = filters[v].process(rng.nextFloat()) * (e1 - e2) * amplitude[v]
            l += out * panLeft[v]
            r += out * panRight[v]
            e1 *= decay1[v]
            env1[v] = e1
            env2[v] = e2 * decay2[v]
            if (e1 < RELEASE_THRESHOLD) releaseAt(j) else j++
        }
        left[index] = l
        right[index] = r
    }
}
