package dev.jtiisto.noise.core.audio.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
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
 *  * **Difference-of-exponentials envelope.** `e^(-t/tau1) - e^(-t/tau2)`
 *    with `tau2 < tau1` starts at exactly zero, rises smoothly (no click, no DC
 *    step) and decays exponentially, for two multiplies per sample and no
 *    branch. The default [startVoice] fixes the attack/decay ratio at 8, so the
 *    peak of the difference is the constant 0.6498 and the normalisation is a
 *    compile-time constant — that is the soft raindrop/bubble onset. The
 *    attack-parameterised [startVoice] overload instead sets `tau2` from an
 *    explicit attack time, so a voice can have a near-instant onset (a fire
 *    snap, ~0.1 ms) independent of its decay; its peak is not constant, so it
 *    pays one `pow` per *spawn* (tens per second, never in the sample loop).
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
        val slot = claimSlot()
        val n = if (durationSamples < 2) 2 else durationSamples
        // exp(-DECAY_SPAN / n) puts the slow term at -40 dB after n samples; the
        // fast term is ATTACK_RATIO times faster (the soft, decay-tied onset).
        decay1[slot] = exp(-DECAY_SPAN / n).toFloat()
        decay2[slot] = exp(-DECAY_SPAN * ATTACK_RATIO / n).toFloat()
        env1[slot] = 1f
        env2[slot] = 1f
        amplitude[slot] = peakAmplitude * PEAK_NORMALISATION
        setPan(slot, pan)
        spawnCount++
        return slot
    }

    /**
     * Like [startVoice] but with the attack set independently of the decay, so
     * a voice can snap open in a fraction of a millisecond and still decay over
     * several. [attackSamples] is the time the fast term takes to fall -40 dB —
     * roughly the onset length; the 10-90 % rise works out near
     * `0.48 * attackSamples`. It is clamped to at most half the decay (the fast
     * term must be faster than the slow one or the difference never rises), and
     * because the difference's peak now depends on the attack/decay ratio the
     * exact normalisation is computed here with one `pow` — at spawn time, never
     * per sample. The onset is still a smooth exponential rise from exactly
     * zero, so it never presents a hard DC step to click on.
     */
    protected fun startVoice(
        durationSamples: Int,
        attackSamples: Int,
        peakAmplitude: Float,
        pan: Float,
    ): Int {
        val slot = claimSlot()
        val n = if (durationSamples < 2) 2 else durationSamples
        val atk = attackSamples.coerceIn(1, n / 2)
        decay1[slot] = exp(-DECAY_SPAN / n).toFloat()
        decay2[slot] = exp(-DECAY_SPAN / atk).toFloat()
        env1[slot] = 1f
        env2[slot] = 1f
        amplitude[slot] = peakAmplitude * differenceNormalisation(atk.toDouble() / n)
        setPan(slot, pan)
        spawnCount++
        return slot
    }

    /**
     * Claims a slot from the free stack, or steals the quietest live voice when
     * the pool is full — for decaying bursts that is the oldest-and-faded one,
     * so stealing it is inaudible.
     */
    private fun claimSlot(): Int {
        if (freeTop > 0) {
            val slot = freeStack[--freeTop]
            activeIndex[activeCount++] = slot
            return slot
        }
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
        return activeIndex[quietest]
    }

    private fun setPan(slot: Int, pan: Float) {
        val p = pan.coerceIn(-1f, 1f)
        // Equal-power pan: cos/sin of the quarter-turn, in the exact sqrt form
        // so a centre-panned event is -3 dB per side.
        panLeft[slot] = sqrt((1f - p) * 0.5f)
        panRight[slot] = sqrt((1f + p) * 0.5f)
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

        /**
         * The fast envelope term decays much faster than the slow one, so with a
         * short attack it drops astronomically small long before the voice
         * releases (release is gated on the slow term). Once it is this far below
         * the slow term — which stays >= [RELEASE_THRESHOLD] while the voice is
         * live — it contributes nothing audible to `e1 - e2`, so it is flushed to
         * exactly zero. That keeps it out of the subnormal-float range, where
         * arithmetic traps to slow microcode on some ARM cores. 1e-20 is ~2^-66:
         * a normal float, tens of orders of magnitude above the subnormal
         * boundary and far below any audible contribution.
         */
        const val ENV2_FLUSH = 1e-20f

        /**
         * Reciprocal of the peak of `e^{-t/T1} - e^{-t/T2}` for a fast term
         * `rho = T2/T1` of the slow one, so that multiplying by it makes the
         * difference peak at exactly 1 whatever the attack/decay ratio. Closed
         * form: the peak is `rho^(rho/(1-rho)) - rho^(1/(1-rho))`. `rho` is
         * clamped to (0, 0.5]; at the 1/8 the constant path uses this returns
         * 1.5389, matching [PEAK_NORMALISATION].
         */
        fun differenceNormalisation(rho: Double): Float {
            val r = rho.coerceIn(1e-3, 0.5)
            val peak = r.pow(r / (1.0 - r)) - r.pow(1.0 / (1.0 - r))
            return (1.0 / peak.coerceAtLeast(1e-3)).toFloat()
        }
    }
}

/**
 * Voices that are bursts of white noise through a per-voice one-pole
 * low-pass — broadband, not resonant. Two uses:
 *
 *  * the classic **raindrop** ([spawn] with the soft, decay-tied onset): a
 *    spectrally random tick whose brightness says how close and how big the
 *    drop was;
 *  * the fire **snap** ([spawn] with an explicit `attackSamples`): the same
 *    broadband tick but with a near-instant onset and a very short decay. A
 *    real fire crack is an impulsive broadband click, not a pitched ring, so
 *    it is deliberately *not* run through a resonance — the one-pole low-pass
 *    only tilts how bright the click is.
 *
 * The output is level-compensated for the low-pass, so choosing a dark cutoff
 * makes a burst *duller*, not *quieter* — otherwise the colour randomisation
 * would double as an unwanted amplitude randomisation.
 */
class NoiseBurstVoicePool(
    capacity: Int,
    private val rng: NoiseRng,
    private val sampleRate: Int,
) : EventVoicePool(capacity) {

    // A voice is white through an optional one-pole high-pass then a one-pole
    // low-pass. With the high-pass off (coefficient 0) it is a plain low-passed
    // raindrop; with it on it is a crisp broad-band fire snap.
    private val hpCoefficient = FloatArray(capacity)
    private val hpLpState = FloatArray(capacity)
    private val lpCoefficient = FloatArray(capacity)
    private val lpState = FloatArray(capacity)

    override fun onReset() {
        for (i in 0 until capacity) {
            hpLpState[i] = 0f
            lpState[i] = 0f
        }
    }

    /** @param cutoffHz one-pole low-pass colour of this burst (no high-pass). */
    fun spawn(durationSamples: Int, peakAmplitude: Float, pan: Float, cutoffHz: Float) {
        installColour(startVoice(durationSamples, peakAmplitude, pan), 0f, cutoffHz)
    }

    /**
     * Fire-snap spawn: a broadband burst with a near-instant onset, optionally
     * *gently* high-passed so it reads as a crisp bright click rather than a
     * dull thud. [highPassHz] <= 20 leaves it un-high-passed. Both corners are
     * one-pole (6 dB/oct), so even high-passed this is a *broad* band well over
     * an octave wide — a coloured broadband click, not a resonant ring.
     * @param attackSamples onset length (see [EventVoicePool.startVoice]).
     */
    fun spawn(
        durationSamples: Int,
        attackSamples: Int,
        peakAmplitude: Float,
        pan: Float,
        highPassHz: Float,
        lowPassHz: Float,
    ) {
        installColour(
            startVoice(durationSamples, attackSamples, peakAmplitude, pan),
            highPassHz,
            lowPassHz,
        )
    }

    private fun installColour(slot: Int, highPassHz: Float, lowPassHz: Float) {
        val fLp = lowPassHz.coerceIn(20f, sampleRate * 0.49f)
        val aLp = (1.0 - exp(-2.0 * Math.PI * fLp / sampleRate)).toFloat()
        lpCoefficient[slot] = aLp
        lpState[slot] = 0f
        hpLpState[slot] = 0f
        // Undo the RMS loss of the filters so the corners control colour only,
        // not loudness.
        var makeup = OnePoleLowPass.whiteRmsGain(aLp).coerceAtLeast(1e-4f)
        if (highPassHz > 20f) {
            val fHp = highPassHz.coerceIn(20f, sampleRate * 0.49f)
            val aHp = (1.0 - exp(-2.0 * Math.PI * fHp / sampleRate)).toFloat()
            hpCoefficient[slot] = aHp
            // White RMS gain of a one-pole high-pass (x - low-pass) is
            // (1-a)*sqrt(2/(2-a)); the two corners are far apart, so treating
            // the cascade as independent one-poles is accurate to well under
            // 1.5 dB (verified numerically) — inside the per-event level spread.
            makeup *= ((1f - aHp) * sqrt(2f / (2f - aHp))).coerceAtLeast(1e-4f)
        } else {
            hpCoefficient[slot] = 0f
        }
        amplitude[slot] = amplitude[slot] / makeup
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
            val white = rng.nextFloat()
            // One-pole high-pass (x - low-pass) feeding a one-pole low-pass: a
            // broad band-pass. With hpCoefficient 0 the high-pass state stays 0
            // and (white - hpLp) == white, so a raindrop spawn is unaffected.
            var hpLp = hpLpState[v]
            hpLp += hpCoefficient[v] * (white - hpLp)
            hpLpState[v] = hpLp
            var s = lpState[v]
            s += lpCoefficient[v] * ((white - hpLp) - s)
            lpState[v] = s
            val out = s * (e1 - e2) * amplitude[v]
            l += out * panLeft[v]
            r += out * panRight[v]
            e1 *= decay1[v]
            env1[v] = e1
            // Flush the fast term once negligible so it never tails into
            // subnormal floats (see ENV2_FLUSH).
            val e2n = e2 * decay2[v]
            env2[v] = if (e2n < ENV2_FLUSH) 0f else e2n
            if (e1 < RELEASE_THRESHOLD) releaseAt(j) else j++
        }
        left[index] = l
        right[index] = r
    }
}

/**
 * Voices that are bursts of white noise through a per-voice resonant
 * band-pass. The campfire's low **woody pop** is the use case: a log settling
 * is a low cavity resonating, so a moderate resonance (Q around 2-5) at a low
 * centre (~150-500 Hz) reads as a woody "tok" with a body, where a plain
 * low-passed thump reads as a dull whump. The [spawn] overload with an
 * explicit `attackSamples` gives it a fast onset like the bright snaps.
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
        installBandPass(startVoice(durationSamples, peakAmplitude, pan), centreHz, q)
    }

    /** @param attackSamples onset length (see [EventVoicePool.startVoice]). */
    fun spawn(
        durationSamples: Int,
        attackSamples: Int,
        peakAmplitude: Float,
        pan: Float,
        centreHz: Float,
        q: Float,
    ) {
        installBandPass(startVoice(durationSamples, attackSamples, peakAmplitude, pan), centreHz, q)
    }

    private fun installBandPass(slot: Int, centreHz: Float, q: Float) {
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
            // Flush the fast term once negligible so it never tails into
            // subnormal floats (see ENV2_FLUSH).
            val e2n = e2 * decay2[v]
            env2[v] = if (e2n < ENV2_FLUSH) 0f else e2n
            if (e1 < RELEASE_THRESHOLD) releaseAt(j) else j++
        }
        left[index] = l
        right[index] = r
    }
}
