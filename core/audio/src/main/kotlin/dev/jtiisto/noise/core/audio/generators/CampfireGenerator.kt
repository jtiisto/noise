package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.SoundGenerator
import dev.jtiisto.noise.core.audio.dsp.Biquad
import dev.jtiisto.noise.core.audio.dsp.BrownNoiseSource
import dev.jtiisto.noise.core.audio.dsp.NoiseBurstVoicePool
import dev.jtiisto.noise.core.audio.dsp.NoiseRng
import dev.jtiisto.noise.core.audio.dsp.OnePoleLowPass
import dev.jtiisto.noise.core.audio.dsp.PoissonClock
import dev.jtiisto.noise.core.audio.dsp.ResonantBurstVoicePool
import dev.jtiisto.noise.core.audio.dsp.SmoothNoise
import kotlin.math.tanh

/**
 * Campfire preset.
 *
 * The crackle is three kinds of event, not one. Real fire cracks — measured
 * against reference recordings — are *impulsive* (a near-instant broadband
 * click, ~0.1 ms rise), *bright/broadband* (energy well up into the multi-kHz
 * range, not a pitched ring), and *varied* (from low woody pops through bright
 * snaps). The earlier single-band resonant crackle was soft, dull, pitched and
 * uniform, which read as artificial and rain-like. So:
 *
 *  * **bright snaps** — the majority (whatever is left after the two below) —
 *    through [snapCutoffMinHz]..[snapCutoffMaxHz];
 *  * [midProbability] worth of dimmer **mid crackles** through
 *    [midCutoffMinHz]..[midCutoffMaxHz];
 *  * [popProbability] worth of low **woody pops** — the body that says "logs" —
 *    a resonant thump around [popCentreMinHz]..[popCentreMaxHz].
 *
 * @param popProbability chance a crackle is the low woody pop rather than a
 *        snap or a mid. Occasional on purpose: it is the loud event, and a
 *        loud event that is common becomes a rhythm.
 */
data class CampfirePreset(
    /**
     * Total crackles per second, drifting slowly between the two over minutes
     * so the fire flares and settles. This is the *audible* crackle density —
     * flurry follow-ons included — so the Poisson trigger clock runs slower by
     * the mean flurry size (see [flurryProbability]).
     */
    val cracklesPerSecondMin: Float = 5f,
    val cracklesPerSecondMax: Float = 12f,

    // --- Flurry / clustering -------------------------------------------------
    /**
     * A real fire does not crack on an even Poisson stream; cracks come in
     * irregular bursts. Each Poisson trigger fires one crack immediately, then
     * spawns up to [flurryMaxExtra] rapid follow-ons: each extra happens with
     * probability [flurryProbability] (a capped geometric tail, mean
     * `p + p^2 + p^3`), spaced [flurryGapMinMs]..[flurryGapMaxMs] apart.
     */
    val flurryProbability: Float = 0.55f,
    val flurryMaxExtra: Int = 3,
    val flurryGapMinMs: Float = 10f,
    val flurryGapMaxMs: Float = 55f,

    // --- Event-type mix ------------------------------------------------------
    val popProbability: Float = 0.09f,
    val midProbability: Float = 0.26f,

    // --- Bright snap (the majority): broadband, near-instant, short ----------
    val snapAttackMinMs: Float = 0.08f,
    val snapAttackMaxMs: Float = 0.35f,
    val snapDecayMinMs: Float = 2f,
    val snapDecayMaxMs: Float = 9f,
    val snapCutoffMinHz: Float = 3_000f,
    val snapCutoffMaxHz: Float = 7_000f,
    /** Gentle high-pass: crisps the click and colours it (real cracks are not
     *  flat white), without narrowing it to a resonant band. */
    val snapHighPassHz: Float = 1_100f,
    val snapLevel: Float = 2.8f,

    // --- Mid crackle: dimmer, a touch longer ---------------------------------
    val midAttackMinMs: Float = 0.15f,
    val midAttackMaxMs: Float = 0.6f,
    val midDecayMinMs: Float = 4f,
    val midDecayMaxMs: Float = 14f,
    val midCutoffMinHz: Float = 1_500f,
    val midCutoffMaxHz: Float = 3_500f,
    val midHighPassHz: Float = 600f,
    val midLevel: Float = 2.5f,

    // --- Low woody pop: a resonant "tok", the body ---------------------------
    val popAttackMinMs: Float = 0.4f,
    val popAttackMaxMs: Float = 1.2f,
    val popDecayMinMs: Float = 8f,
    val popDecayMaxMs: Float = 22f,
    val popCentreMinHz: Float = 150f,
    val popCentreMaxHz: Float = 480f,
    val popQMin: Float = 1.5f,
    val popQMax: Float = 3.5f,
    val popLevel: Float = 3.5f,

    // --- Beds: a low rumble and a bright sizzle ------------------------------
    // A bright crackling fire is dominated by its high end (a real reference
    // render is only ~8 % below 120 Hz and ~60 % above 3 kHz), so the rumble is
    // a supporting bed here rather than the body — cut well down from the older
    // value that buried the cracks — and the hiss is louder and wider.
    val rumbleCutoffHz: Float = 120f,
    val rumbleLevel: Float = 0.10f,
    val rumbleFlutterHz: Float = 0.35f,
    val hissLowHz: Float = 2_500f,
    val hissHighHz: Float = 9_000f,
    val hissLevel: Float = 0.14f,
    val hissFlutterMinHz: Float = 5f,
    val hissFlutterMaxHz: Float = 12f,

    val snapVoices: Int = 24,
    val popVoices: Int = 8,
    val outputGain: Float = 0.98f,
) {
    companion object {
        val DEFAULT = CampfirePreset()
    }
}

/**
 * Campfire — a low rumble, a band of hiss and irregular flurries of crackles.
 *
 *  * **Rumble.** Brown noise under 120 Hz with a slow flutter. This is the
 *    convection column, and it is what makes a fire feel *near*; without it
 *    the crackles sound like a recording playing on a small speaker.
 *  * **Hiss.** White through a 2.5-9 kHz band with a fast (5-12 Hz) random
 *    amplitude flutter: the continuous steam sizzle. The flutter rate is itself
 *    randomised, because a fixed rate reads as a tremolo.
 *  * **Crackles.** Three kinds of impulsive event, emitted in irregular
 *    flurries rather than an even stream (see [CampfirePreset]):
 *      - **bright snaps** — the majority — a broadband white burst with a
 *        near-instant (~0.1-0.35 ms) attack and a short (2-9 ms) decay, gently
 *        high-passed then low-passed high up so it stays a bright broad band. No
 *        resonance: a real crack is an impulsive click, not a pitched ring, so
 *        running it through a narrow band-pass (as the old design did) is what
 *        made it read as static/rain;
 *      - **mid crackles** — dimmer, a touch longer, through a lower band;
 *      - **low woody pops** — occasional, the loud body: a resonant thump
 *        around 150-480 Hz with a slightly longer decay, the detail that says
 *        "logs" rather than "just a louder crack".
 *
 * The character was tuned by comparing the offline render's per-crack
 * transient metrics (attack sharpness, spectral flatness, centroid, and their
 * spread) against real campfire recordings; see docs/sound-design.md.
 */
class CampfireGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: CampfirePreset = CampfirePreset.DEFAULT,
) : SoundGenerator {

    private val rng = NoiseRng(streamSeed(seed, 61))
    private val rumbleRngLeft = NoiseRng(streamSeed(seed, 62))
    private val rumbleRngRight = NoiseRng(streamSeed(seed, 63))
    private val hissRngLeft = NoiseRng(streamSeed(seed, 64))
    private val hissRngRight = NoiseRng(streamSeed(seed, 65))
    private val crackleRng = NoiseRng(streamSeed(seed, 66))

    private val rumbleLeft = BrownNoiseSource(sampleRate, rumbleRngLeft, 10f, 55f)
    private val rumbleRight = BrownNoiseSource(sampleRate, rumbleRngRight, 10f, 55f)
    private val rumbleLowPassLeft = OnePoleLowPass(sampleRate, preset.rumbleCutoffHz)
    private val rumbleLowPassRight = OnePoleLowPass(sampleRate, preset.rumbleCutoffHz)
    private val rumbleFlutter = SmoothNoise(sampleRate, rng, preset.rumbleFlutterHz)

    private val hissHighPassLeft = Biquad()
    private val hissHighPassRight = Biquad()
    private val hissLowPassLeft = Biquad()
    private val hissLowPassRight = Biquad()
    private val hissFlutter = SmoothNoise(
        sampleRate,
        rng,
        (preset.hissFlutterMinHz + preset.hissFlutterMaxHz) * 0.5f,
    )

    // The trigger clock schedules flurry *heads*; it runs at the total crackle
    // rate divided by the mean flurry size so the audible density lands in the
    // preset's cracklesPerSecond range.
    private val meanFlurrySize = meanFlurrySize(preset.flurryProbability, preset.flurryMaxExtra)
    private val crackleClock =
        PoissonClock(sampleRate, crackleRng, preset.cracklesPerSecondMin / meanFlurrySize)
    private val snaps = NoiseBurstVoicePool(preset.snapVoices, crackleRng, sampleRate)
    private val pops = ResonantBurstVoicePool(preset.popVoices, crackleRng, sampleRate)
    private val rateDrift = SmoothNoise(
        (sampleRate / CONTROL_PERIOD).coerceAtLeast(1),
        rng,
        RATE_DRIFT_HZ,
    )
    private var controlCountdown = 0

    // Flurry scheduler state (allocation-free: two ints, no queue).
    private var flurryRemaining = 0
    private var flurryCountdown = 0

    /** Crackles started since construction / [reset]; used by tests. */
    val crackleCount: Long get() = snaps.spawnCount + pops.spawnCount

    init {
        configureHiss()
    }

    private fun configureHiss() {
        hissHighPassLeft.setHighPass(sampleRate, preset.hissLowHz, HISS_Q)
        hissHighPassRight.setHighPass(sampleRate, preset.hissLowHz, HISS_Q)
        hissLowPassLeft.setLowPass(sampleRate, preset.hissHighHz, HISS_Q)
        hissLowPassRight.setLowPass(sampleRate, preset.hissHighHz, HISS_Q)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain
        val rumbleLevel = preset.rumbleLevel
        val hissLevel = preset.hissLevel

        for (i in 0 until frames) {
            if (--controlCountdown <= 0) {
                controlCountdown = CONTROL_PERIOD
                val t = (rateDrift.next() + 1f) * 0.5f
                val totalRate = preset.cracklesPerSecondMin +
                    (preset.cracklesPerSecondMax - preset.cracklesPerSecondMin) * t
                crackleClock.setRate(totalRate / meanFlurrySize)
            }

            val rumbleGain = rumbleLevel * (1f + RUMBLE_FLUTTER_DEPTH * rumbleFlutter.next())
            val hissGain = hissLevel * (1f + HISS_FLUTTER_DEPTH * hissFlutter.next())

            var l = rumbleLowPassLeft.process(rumbleLeft.next()) * rumbleGain
            var r = rumbleLowPassRight.process(rumbleRight.next()) * rumbleGain
            l += hissLowPassLeft.process(hissHighPassLeft.process(hissRngLeft.nextFloat())) *
                hissGain
            r += hissLowPassRight.process(hissHighPassRight.process(hissRngRight.nextFloat())) *
                hissGain

            left[i] = l
            right[i] = r

            // A Poisson trigger is a flurry head; a flurry then drips out its
            // rapid follow-ons. The two are independent so a fresh head can
            // land during a flurry (it simply restarts the flurry).
            val flurryStarted = crackleClock.tick()
            if (flurryStarted) {
                spawnCrackle()
                startFlurry()
            }
            // Do not count down on the sample a flurry was (re)started: the gap
            // is measured from the head, so the first follow-on lands a full gap
            // later (a gap of one sample must not coincide with the head).
            if (!flurryStarted && flurryRemaining > 0 && --flurryCountdown <= 0) {
                spawnCrackle()
                flurryRemaining--
                if (flurryRemaining > 0) flurryCountdown = drawFlurryGap()
            }

            snaps.addSample(left, right, i)
            pops.addSample(left, right, i)

            // A crackling fire is inherently very peaky (sparse loud cracks over
            // a quiet bed, crest ~27 dB) while calibration wants -20 dBFS RMS
            // under the 0.9 peak ceiling (~19 dB crest). A bed loud enough to
            // close that gap would swamp the cracks, so the beds stay low and
            // this soft limiter catches only the loudest few cracks — the knee is
            // high enough that the beds and ordinary cracks pass through
            // untouched, so their sharp onset (the impact) is preserved.
            left[i] = softLimit(left[i] * gain)
            right[i] = softLimit(right[i] * gain)
        }
    }

    private fun softLimit(x: Float): Float {
        val a = if (x < 0f) -x else x
        if (a <= LIMIT_KNEE) return x
        val over = (a - LIMIT_KNEE) / (LIMIT_CEIL - LIMIT_KNEE)
        val shaped = LIMIT_KNEE + (LIMIT_CEIL - LIMIT_KNEE) * tanh(over)
        return if (x < 0f) -shaped else shaped
    }

    /** Emits one crackle, choosing its kind from the preset's type mix. */
    private fun spawnCrackle() {
        val type = crackleRng.nextUnit()
        val pan = crackleRng.nextFloat()
        when {
            type < preset.popProbability -> {
                // Low woody pop — the resonant body. More consistently present
                // than the snaps (it is the loud event), so less level spread.
                val u = crackleRng.nextUnit()
                val amp = preset.popLevel * (0.65f + 0.35f * u)
                val decay = msToSamples(crackleRng.nextRange(preset.popDecayMinMs, preset.popDecayMaxMs))
                val attack = msToSamples(crackleRng.nextRange(preset.popAttackMinMs, preset.popAttackMaxMs))
                val centre = crackleRng.nextRange(preset.popCentreMinHz, preset.popCentreMaxHz)
                val q = crackleRng.nextRange(preset.popQMin, preset.popQMax)
                pops.spawn(decay, attack, amp, pan, centre, q)
            }
            type < preset.popProbability + preset.midProbability -> {
                val u = crackleRng.nextUnit()
                val amp = preset.midLevel * (0.55f + 0.45f * u)
                val decay = msToSamples(crackleRng.nextRange(preset.midDecayMinMs, preset.midDecayMaxMs))
                val attack = msToSamples(crackleRng.nextRange(preset.midAttackMinMs, preset.midAttackMaxMs))
                val cutoff = crackleRng.nextRange(preset.midCutoffMinHz, preset.midCutoffMaxHz)
                snaps.spawn(decay, attack, amp, pan, preset.midHighPassHz, cutoff)
            }
            else -> {
                // Bright sharp snap. A fairly flat level spread (not the
                // squared-uniform the rain drops use): a real fire's cracks do
                // vary, but a heavy tail of rare-loud snaps just inflates the
                // crest factor, which a -20 dBFS calibration then has to limit
                // away — so the spread is kept modest and the sharp onset, not
                // the peak height, carries the impact.
                val u = crackleRng.nextUnit()
                val amp = preset.snapLevel * (0.55f + 0.45f * u)
                val decay = msToSamples(crackleRng.nextRange(preset.snapDecayMinMs, preset.snapDecayMaxMs))
                val attack = msToSamples(crackleRng.nextRange(preset.snapAttackMinMs, preset.snapAttackMaxMs))
                val cutoff = crackleRng.nextRange(preset.snapCutoffMinHz, preset.snapCutoffMaxHz)
                snaps.spawn(decay, attack, amp, pan, preset.snapHighPassHz, cutoff)
            }
        }
    }

    private fun startFlurry() {
        var extras = 0
        while (extras < preset.flurryMaxExtra && crackleRng.nextBoolean(preset.flurryProbability)) {
            extras++
        }
        flurryRemaining = extras
        if (flurryRemaining > 0) flurryCountdown = drawFlurryGap()
    }

    private fun drawFlurryGap(): Int =
        msToSamples(crackleRng.nextRange(preset.flurryGapMinMs, preset.flurryGapMaxMs))

    private fun msToSamples(ms: Float): Int = (ms * sampleRate / 1000f).toInt().coerceAtLeast(1)

    override fun reset(seed: Long) {
        rng.reseed(streamSeed(seed, 61))
        rumbleRngLeft.reseed(streamSeed(seed, 62))
        rumbleRngRight.reseed(streamSeed(seed, 63))
        hissRngLeft.reseed(streamSeed(seed, 64))
        hissRngRight.reseed(streamSeed(seed, 65))
        crackleRng.reseed(streamSeed(seed, 66))
        rumbleLeft.reset()
        rumbleRight.reset()
        rumbleLowPassLeft.reset()
        rumbleLowPassRight.reset()
        rumbleFlutter.reset()
        hissHighPassLeft.reset()
        hissHighPassRight.reset()
        hissLowPassLeft.reset()
        hissLowPassRight.reset()
        hissFlutter.reset()
        // Restore the clock's rate before resetting it: the drift will have
        // left it somewhere else, and the first gap after a reset is drawn from
        // whatever rate is current, which would make reset(seed) non-reproducible.
        crackleClock.setRate(preset.cracklesPerSecondMin / meanFlurrySize)
        crackleClock.reset()
        snaps.reset()
        pops.reset()
        rateDrift.reset()
        controlCountdown = 0
        flurryRemaining = 0
        flurryCountdown = 0
    }

    private companion object {
        const val HISS_Q = 0.707f
        const val CONTROL_PERIOD = 64
        const val RATE_DRIFT_HZ = 0.05f
        const val RUMBLE_FLUTTER_DEPTH = 0.30f
        const val HISS_FLUTTER_DEPTH = 0.55f

        /** Soft-limiter knee/ceiling (post-gain). Below the knee the signal is
         *  untouched; above it, peaks asymptote to the ceiling, which sits under
         *  the 0.9 headroom gate. */
        const val LIMIT_KNEE = 0.62f
        const val LIMIT_CEIL = 0.88f

        /**
         * Mean number of cracks per Poisson trigger: 1 head plus a capped
         * geometric tail of extras, whose mean is `p + p^2 + ... + p^maxExtra`.
         * Used to scale the trigger rate so the total crack rate matches the
         * preset.
         */
        fun meanFlurrySize(p: Float, maxExtra: Int): Float {
            var mean = 1f
            var term = 1f
            for (k in 1..maxExtra) {
                term *= p
                mean += term
            }
            return mean
        }
    }
}
