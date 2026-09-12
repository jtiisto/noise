package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.SoundGenerator
import dev.jtiisto.noise.core.audio.dsp.Biquad
import dev.jtiisto.noise.core.audio.dsp.BrownNoiseSource
import dev.jtiisto.noise.core.audio.dsp.DualExponential
import dev.jtiisto.noise.core.audio.dsp.NoiseRng

/**
 * @param minIntervalSeconds / [maxIntervalSeconds] gap between rolls.
 * @param firstMinSeconds / [firstMaxSeconds] gap before the *first* roll —
 *        deliberately much shorter, so a listener who selects "thunderstorm"
 *        hears thunder within the first quarter minute instead of wondering
 *        whether they picked the wrong sound. It also makes the 20 s offline
 *        render contain a roll.
 * @param peakAmplitude peak amplitude of a roll. Held at ~2x the rain bed's
 *        RMS, i.e. +6 dB, which the spec caps deliberately: this is a sleep
 *        app and a roll must never be a jump scare.
 */
data class ThunderPreset(
    val minIntervalSeconds: Float = 25f,
    val maxIntervalSeconds: Float = 90f,
    val firstMinSeconds: Float = 5f,
    val firstMaxSeconds: Float = 15f,
    val minDurationSeconds: Float = 4f,
    val maxDurationSeconds: Float = 9f,
    val minCutoffHz: Float = 40f,
    val maxCutoffHz: Float = 220f,
    /**
     * How far the roll's low-pass has fallen by the end of the event. Thunder
     * darkens as it decays: the later arrivals have travelled further through
     * air and off more surfaces, and air absorption is strongly
     * frequency-dependent. A roll whose timbre is constant reads as a
     * filtered noise burst rather than as distance.
     */
    val endCutoffFraction: Float = 0.45f,
    /** Fraction of the event length over which a bump decays to -40 dB. */
    val bumpDecayFraction: Float = 0.70f,
    val minAttackMs: Float = 30f,
    val maxAttackMs: Float = 150f,
    val maxSubRolls: Int = 3,
    val crackProbability: Float = 0.25f,
    val peakAmplitude: Float = 0.180f,
    val bedTrim: Float = 0.90f,
) {
    companion object {
        val DEFAULT = ThunderPreset()
    }
}

/**
 * Thunderstorm = the downpour bed plus rare, soft, distant rolls.
 *
 * A roll is modelled the way thunder actually reaches a listener several
 * kilometres away: the discharge is broadband, but air absorption and ground
 * reflections strip everything above a couple of hundred hertz, and the sound
 * arrives smeared over seconds because different parts of the channel are
 * different distances away. So:
 *
 *  * the source is brown noise (already -6 dB/oct) through a 2nd-order
 *    low-pass drawn per event between 40 and 220 Hz — near rolls are brighter;
 *  * the envelope is 2-4 overlapping difference-of-exponentials "bumps"
 *    spread over the first half of the event, which is what gives a roll its
 *    characteristic re-swelling rather than a single decaying thud;
 *  * one roll in four also gets a short band-passed "crack" at onset, the
 *    direct path arriving before the smeared reflections. It is deliberately
 *    quiet — a full-level crack wakes people up.
 *
 * Left and right take independent brown streams under a shared envelope: the
 * roll is one event happening at both ears, but the air path decorrelates the
 * fine structure, which is why real thunder feels enveloping rather than
 * localised.
 */
class ThunderstormGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: ThunderPreset = ThunderPreset.DEFAULT,
    rainPreset: RainPreset = RainPreset.DOWNPOUR,
) : SoundGenerator {

    private val rain = RainGenerator(sampleRate, streamSeed(seed, 31), rainPreset, preset.bedTrim)
    private val rng = NoiseRng(streamSeed(seed, 32))
    private val brownLeft = BrownNoiseSource(sampleRate, NoiseRng(streamSeed(seed, 33)))
    private val brownRight = BrownNoiseSource(sampleRate, NoiseRng(streamSeed(seed, 34)))
    private val crackRng = NoiseRng(streamSeed(seed, 35))

    private val toneLeft = Biquad()
    private val toneRight = Biquad()
    private val crackBand = Biquad()

    // Up to four overlapping envelope bumps make one roll.
    private val bumpDelay = IntArray(MAX_BUMPS)
    private val bumpAmplitude = FloatArray(MAX_BUMPS)
    private val bumpAttackCoefficient = FloatArray(MAX_BUMPS)
    private val bumpDecayCoefficient = FloatArray(MAX_BUMPS)
    private val bumpAttackState = FloatArray(MAX_BUMPS)
    private val bumpDecayState = FloatArray(MAX_BUMPS)
    private val bumpStarted = BooleanArray(MAX_BUMPS)
    private var bumpCount = 0

    private var samplesUntilRoll = 0
    private var rollSamplesLeft = 0
    private var rollElapsed = 0
    private var rollDurationSamples = 1
    private var rollCutoffStart = 0f
    private var rollCutoffEnd = 0f
    private var controlCountdown = 0

    private var crackAttackState = 0f
    private var crackDecayState = 0f
    private var crackAttackCoefficient = 0f
    private var crackDecayCoefficient = 0f
    private var crackAmplitude = 0f
    private var crackSamplesLeft = 0

    /** Rolls started since construction / [reset]; used by tests. */
    var rollCount: Long = 0L
        private set

    init {
        scheduleFirstRoll()
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        rain.render(left, right, frames)

        for (i in 0 until frames) {
            // One free-running countdown drives everything: it is reloaded on
            // each roll with "this roll's length + the gap to the next", so
            // rolls can never overlap and the schedule needs no state machine.
            if (--samplesUntilRoll <= 0) startRoll()
            if (rollSamplesLeft > 0) {
                if (--controlCountdown <= 0) {
                    controlCountdown = CONTROL_PERIOD
                    darkenRoll()
                }
                val envelope = advanceRollEnvelope()
                if (envelope > 0f) {
                    val amplitude = envelope * preset.peakAmplitude
                    left[i] += toneLeft.process(brownLeft.next()) * amplitude
                    right[i] += toneRight.process(brownRight.next()) * amplitude
                }
                rollSamplesLeft--
                rollElapsed++
            }
            if (crackSamplesLeft > 0) {
                val e = crackDecayState - crackAttackState
                crackDecayState *= crackDecayCoefficient
                crackAttackState *= crackAttackCoefficient
                crackSamplesLeft--
                if (e > 0f) {
                    val s = crackBand.process(crackRng.nextFloat()) * e * crackAmplitude
                    // The crack is the direct path: narrow, and centred rather
                    // than spread, so it reads as a single distant snap.
                    left[i] += s
                    right[i] += s
                }
            }
        }
    }

    override fun reset(seed: Long) {
        rain.reset(streamSeed(seed, 31))
        rng.reseed(streamSeed(seed, 32))
        brownLeft.reset()
        brownRight.reset()
        crackRng.reseed(streamSeed(seed, 35))
        toneLeft.reset()
        toneRight.reset()
        crackBand.reset()
        rollSamplesLeft = 0
        rollElapsed = 0
        crackSamplesLeft = 0
        controlCountdown = 0
        bumpCount = 0
        rollCount = 0L
        scheduleFirstRoll()
    }

    private fun scheduleFirstRoll() {
        samplesUntilRoll =
            (rng.nextRange(preset.firstMinSeconds, preset.firstMaxSeconds) * sampleRate)
                .toInt().coerceAtLeast(1)
    }

    private fun startRoll() {
        val duration = rng.nextRange(preset.minDurationSeconds, preset.maxDurationSeconds)
        val durationSamples = (duration * sampleRate).toInt()
        // The *rendered* window is longer than the nominal event, because the
        // last sub-roll starts 60 % of the way in and then needs its own decay
        // to finish. Stopping at the nominal length would chop a still-audible
        // tail off, which is a click; the cutoff sweep holds at its end value
        // through the extra time so the tail stays dark.
        rollSamplesLeft = (durationSamples * TAIL_FACTOR).toInt().coerceAtLeast(1)
        rollElapsed = 0
        rollCount++
        val gap = rng.nextRange(preset.minIntervalSeconds, preset.maxIntervalSeconds) * sampleRate
        samplesUntilRoll = (rollSamplesLeft + gap.toInt()).coerceAtLeast(1)

        rollDurationSamples = durationSamples.coerceAtLeast(1)
        rollCutoffStart = rng.nextRange(preset.minCutoffHz, preset.maxCutoffHz)
        rollCutoffEnd = rollCutoffStart * preset.endCutoffFraction
        controlCountdown = 0
        toneLeft.setLowPass(sampleRate, rollCutoffStart, TONE_Q)
        toneRight.setLowPass(sampleRate, rollCutoffStart, TONE_Q)

        bumpCount = 2 + rng.nextInt(preset.maxSubRolls.coerceAtLeast(1))
        if (bumpCount > MAX_BUMPS) bumpCount = MAX_BUMPS
        // Bumps land in the first ~55 % of the event; the tail is the last
        // bump's decay, which is what makes a roll fade rather than stop.
        val spread = durationSamples * 0.60f
        val decaySamples = durationSamples * preset.bumpDecayFraction
        for (b in 0 until bumpCount) {
            bumpDelay[b] = if (b == 0) 0 else (spread * rng.nextUnit()).toInt()
            bumpAmplitude[b] = if (b == 0) rng.nextRange(0.8f, 1f) else rng.nextRange(0.3f, 0.75f)
            val attackSamples = rng.nextRange(preset.minAttackMs, preset.maxAttackMs) *
                sampleRate / 1000f
            bumpAttackCoefficient[b] = DualExponential.decayCoefficient(attackSamples)
            bumpDecayCoefficient[b] = DualExponential.decayCoefficient(decaySamples)
            // Normalise so bumpAmplitude really is this bump's peak.
            bumpAmplitude[b] /= DualExponential.peak(decaySamples / attackSamples)
            bumpAttackState[b] = 0f
            bumpDecayState[b] = 0f
            bumpStarted[b] = false
        }

        if (rng.nextBoolean(preset.crackProbability)) startCrack()
    }

    private fun startCrack() {
        val decaySamples = rng.nextRange(60f, 150f) * sampleRate / 1000f
        val attackSamples = decaySamples / 12f
        crackDecayCoefficient = DualExponential.decayCoefficient(decaySamples)
        crackAttackCoefficient = DualExponential.decayCoefficient(attackSamples)
        crackDecayState = 1f
        crackAttackState = 1f
        crackSamplesLeft = (decaySamples * 2f).toInt()
        crackBand.setBandPass(sampleRate, rng.nextRange(1_200f, 2_200f), 1.2f)
        crackBand.reset()
        crackAmplitude = preset.peakAmplitude * CRACK_LEVEL /
            DualExponential.peak(decaySamples / attackSamples)
    }

    /** Sweeps the roll's low-pass down as the event proceeds. */
    private fun darkenRoll() {
        val progress = (rollElapsed.toFloat() / rollDurationSamples).coerceIn(0f, 1f)
        val cutoff = rollCutoffStart + (rollCutoffEnd - rollCutoffStart) * progress
        toneLeft.setLowPass(sampleRate, cutoff, TONE_Q)
        toneRight.setLowPass(sampleRate, cutoff, TONE_Q)
    }

    /** Sum of the active bumps, clamped to 1 so overlapping bumps cannot exceed the cap. */
    private fun advanceRollEnvelope(): Float {
        var sum = 0f
        for (b in 0 until bumpCount) {
            if (rollElapsed < bumpDelay[b]) continue
            if (!bumpStarted[b]) {
                bumpStarted[b] = true
                bumpDecayState[b] = 1f
                bumpAttackState[b] = 1f
            }
            val e = bumpDecayState[b] - bumpAttackState[b]
            bumpDecayState[b] *= bumpDecayCoefficient[b]
            bumpAttackState[b] *= bumpAttackCoefficient[b]
            if (e > 0f) sum += e * bumpAmplitude[b]
        }
        return if (sum > 1f) 1f else sum
    }

    private companion object {
        const val MAX_BUMPS = 4
        const val TONE_Q = 0.707f
        const val CONTROL_PERIOD = 64
        /** Rendered roll length as a multiple of the nominal event duration. */
        const val TAIL_FACTOR = 1.6f
        /** The crack sits 9 dB under the roll's own peak — audible, never startling. */
        const val CRACK_LEVEL = 0.35f
    }
}
