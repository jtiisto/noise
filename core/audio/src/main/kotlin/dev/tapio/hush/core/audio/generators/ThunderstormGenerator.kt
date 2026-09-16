package dev.tapio.hush.core.audio.generators

import dev.tapio.hush.core.audio.SoundGenerator
import dev.tapio.hush.core.audio.dsp.Biquad
import dev.tapio.hush.core.audio.dsp.BrownNoiseSource
import dev.tapio.hush.core.audio.dsp.DualExponential
import dev.tapio.hush.core.audio.dsp.NoiseRng
import kotlin.math.tanh

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
    val minIntervalSeconds: Float = 15f,
    val maxIntervalSeconds: Float = 45f,
    val firstMinSeconds: Float = 5f,
    val firstMaxSeconds: Float = 15f,
    val minDurationSeconds: Float = 5f,
    val maxDurationSeconds: Float = 11f,
    val minCutoffHz: Float = 60f,
    val maxCutoffHz: Float = 400f,
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
    val crackProbability: Float = 0.7f,
    val peakAmplitude: Float = 0.38f,
    val bedTrim: Float = 0.90f,
) {
    companion object {
        val DEFAULT = ThunderPreset()
    }
}

/**
 * Thunderstorm = its own darker, heavier storm bed (not the Downpour preset)
 * plus occasional soft, distant rolls.
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
 *  * the *rumble* is the body: a prominent low-frequency rolling swell that
 *    rises above the storm bed so the listener clearly hears thunder rolling,
 *    not just the rain. The bed's low end is kept moderate so it does not mask
 *    the roll. Many rolls also open with a short, soft 1.2-2.6 kHz "crack" —
 *    the direct path before the smeared reflections — as a leading edge, kept
 *    well below a startling clap.
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
    rainPreset: RainPreset = RainPreset.STORM,
) : SoundGenerator {

    private val rain = RainGenerator(sampleRate, streamSeed(seed, 31), rainPreset, preset.bedTrim)
    private val rng = NoiseRng(streamSeed(seed, 32))
    // Held so reset(seed) can reseed them (BrownNoiseSource.reset clears only
    // the filter state); otherwise the rumble would not be reproducible after a
    // reset once a roll had drawn brown noise.
    private val brownRngLeft = NoiseRng(streamSeed(seed, 33))
    private val brownRngRight = NoiseRng(streamSeed(seed, 34))
    private val brownLeft = BrownNoiseSource(sampleRate, brownRngLeft)
    private val brownRight = BrownNoiseSource(sampleRate, brownRngRight)
    private val crackRng = NoiseRng(streamSeed(seed, 35))
    private val midRngLeft = NoiseRng(streamSeed(seed, 36))
    private val midRngRight = NoiseRng(streamSeed(seed, 37))

    private val toneLeft = Biquad()
    private val toneRight = Biquad()
    private val crackBand = Biquad()
    // A low-mid "body" band for the roll. The deep brown rumble carries the
    // weight on speakers with real low end, but a phone speaker rolls off hard
    // below ~350 Hz and cannot reproduce it at all, so the roll would be silent
    // on the target device. This band (~400 Hz-1.3 kHz) puts the roll where a
    // phone can play it, so thunder rolls audibly everywhere.
    private val midLeft = Biquad()
    private val midRight = Biquad()

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
        configureMid()
        scheduleFirstRoll()
    }

    private fun configureMid() {
        midLeft.reset()
        midRight.reset()
        midLeft.setBandPass(sampleRate, MID_CENTRE_HZ, MID_Q)
        midRight.setBandPass(sampleRate, MID_CENTRE_HZ, MID_Q)
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
                    // Duck the rain bed under the roll (proportional to the roll
                    // envelope) so the thunder stands out — essential on a phone
                    // speaker, which cannot reproduce the deep rumble and where
                    // the roll would otherwise be buried under the steady rain.
                    val duck = 1f - ROLL_DUCK_DEPTH * envelope
                    left[i] *= duck
                    right[i] *= duck
                    val amplitude = envelope * preset.peakAmplitude
                    // The mid body is an onset element: strongest at the strike
                    // and gone by the tail, so the roll darkens to a pure deep
                    // rumble the way real thunder does (the mid/direct arrivals
                    // fade first, the low rumble lingers). It also gives a phone
                    // speaker a cue at the onset without keeping high content in
                    // the tail that would flatten the darkening.
                    val midFade = 1f - (rollElapsed.toFloat() / rollDurationSamples).coerceIn(0f, 1f)
                    val mid = MID_LEVEL * midFade
                    left[i] += (toneLeft.process(brownLeft.next()) * BROWN_LEVEL +
                        midLeft.process(midRngLeft.nextFloat()) * mid) * amplitude
                    right[i] += (toneRight.process(brownRight.next()) * BROWN_LEVEL +
                        midRight.process(midRngRight.nextFloat()) * mid) * amplitude
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

            // Keep the generator's own output under the mix-headroom ceiling: a
            // loud roll + bed + crack coincidence can sum past 1.5, which the
            // downstream MixRenderer clipper would then distort on every roll.
            // Below the knee — the bed and ordinary rolls — this is a no-op, so
            // it only shaves the rare peak. It also pulls the RMS back into the
            // calibration window, since the loudest rolls no longer run away.
            left[i] = softLimit(left[i])
            right[i] = softLimit(right[i])
        }
    }

    private fun softLimit(x: Float): Float {
        val a = if (x < 0f) -x else x
        if (a <= LIMIT_KNEE) return x
        val over = (a - LIMIT_KNEE) / (LIMIT_CEIL - LIMIT_KNEE)
        val shaped = LIMIT_KNEE + (LIMIT_CEIL - LIMIT_KNEE) * tanh(over)
        return if (x < 0f) -shaped else shaped
    }

    override fun reset(seed: Long) {
        rain.reset(streamSeed(seed, 31))
        rng.reseed(streamSeed(seed, 32))
        brownRngLeft.reseed(streamSeed(seed, 33))
        brownRngRight.reseed(streamSeed(seed, 34))
        brownLeft.reset()
        brownRight.reset()
        crackRng.reseed(streamSeed(seed, 35))
        midRngLeft.reseed(streamSeed(seed, 36))
        midRngRight.reseed(streamSeed(seed, 37))
        toneLeft.reset()
        toneRight.reset()
        crackBand.reset()
        configureMid()
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
        val decaySamples = rng.nextRange(50f, 130f) * sampleRate / 1000f
        val attackSamples = decaySamples / 12f
        crackDecayCoefficient = DualExponential.decayCoefficient(decaySamples)
        crackAttackCoefficient = DualExponential.decayCoefficient(attackSamples)
        crackDecayState = 1f
        crackAttackState = 1f
        crackSamplesLeft = (decaySamples * 2f).toInt()
        crackBand.setBandPass(sampleRate, rng.nextRange(1_200f, 2_600f), 1.2f)
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
        /** Low-mid "body" band of the roll, above a phone speaker's low-end
         *  roll-off (~350 Hz) and below the hissy region, so the rumble is
         *  audible on the built-in speaker of the target device. */
        const val MID_CENTRE_HZ = 720f
        const val MID_Q = 0.8f
        /** Level of the phone-audible mid body relative to the deep rumble. A
         *  band-passed white burst is far quieter than its input, so this is
         *  large; it is fitted by measurement, not a raw ratio. */
        const val MID_LEVEL = 3.0f
        /** Deep brown rumble weight. Below a phone speaker's roll-off it is
         *  inaudible yet peaks hard, so it is held back from the old implicit
         *  1.0 to free headroom for the phone-audible mid body. */
        const val BROWN_LEVEL = 1.0f
        /** How far the rain bed ducks under a roll at the envelope's peak
         *  (0.6 = about -8 dB), so thunder reads clearly on a small speaker. */
        const val ROLL_DUCK_DEPTH = 0.25f

        /** Soft-limiter knee/ceiling on the generator's own output, so a loud
         *  roll never presents a past-full-scale peak to the mix clipper. */
        const val LIMIT_KNEE = 0.62f
        const val LIMIT_CEIL = 0.88f
        const val CONTROL_PERIOD = 64
        /** Rendered roll length as a multiple of the nominal event duration. */
        const val TAIL_FACTOR = 1.6f
        /** A modest bright onset snap (1.2-2.6 kHz) that gives a roll its
         *  leading edge. The *rumble* is the body of the sound the listener is
         *  meant to hear — a prominent low rolling swell — so the crack is kept
         *  soft (a fraction of the rumble's level): a distant edge, never a
         *  startling clap. */
        const val CRACK_LEVEL = 1.5f
    }
}
