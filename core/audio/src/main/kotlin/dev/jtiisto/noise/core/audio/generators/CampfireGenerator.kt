package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.SoundGenerator
import dev.jtiisto.noise.core.audio.dsp.Biquad
import dev.jtiisto.noise.core.audio.dsp.BrownNoiseSource
import dev.jtiisto.noise.core.audio.dsp.NoiseRng
import dev.jtiisto.noise.core.audio.dsp.OnePoleLowPass
import dev.jtiisto.noise.core.audio.dsp.PoissonClock
import dev.jtiisto.noise.core.audio.dsp.ResonantBurstVoicePool
import dev.jtiisto.noise.core.audio.dsp.SmoothNoise

/**
 * @param popProbability chance that a crackle is instead a loud "pop". One in
 *        fifteen: often enough to keep the fire interesting, rare enough that
 *        it never becomes a rhythm.
 */
data class CampfirePreset(
    val cracklesPerSecondMin: Float = 4f,
    val cracklesPerSecondMax: Float = 12f,
    val crackleMinMs: Float = 3f,
    val crackleMaxMs: Float = 25f,
    val crackleMinHz: Float = 900f,
    val crackleMaxHz: Float = 5_000f,
    val crackleQMin: Float = 5f,
    val crackleQMax: Float = 14f,
    val crackleLevel: Float = 1.0f,
    val popProbability: Float = 1f / 15f,
    val popGain: Float = 3.2f,
    val rumbleCutoffHz: Float = 120f,
    val rumbleLevel: Float = 0.45f,
    val rumbleFlutterHz: Float = 0.35f,
    val hissLowHz: Float = 3_000f,
    val hissHighHz: Float = 7_000f,
    /** -16 dB relative to the rumble, per the spec. */
    val hissLevel: Float = 0.28f,
    val hissFlutterMinHz: Float = 5f,
    val hissFlutterMaxHz: Float = 12f,
    val voices: Int = 16,
    val outputGain: Float = 0.2755f,
) {
    companion object {
        val DEFAULT = CampfirePreset()
    }
}

/**
 * Campfire — a low rumble, a band of hiss and a Poisson stream of resonant
 * crackles.
 *
 *  * **Rumble.** Brown noise under 120 Hz with a slow flutter. This is the
 *    convection column, and it is what makes a fire feel *near*; without it
 *    the crackles sound like a recording playing on a small speaker.
 *  * **Hiss.** White through a 3-7 kHz band with a fast (5-12 Hz) random
 *    amplitude flutter: steam escaping from the wood. The flutter rate is
 *    itself randomised, because a fixed rate reads as a tremolo.
 *  * **Crackles.** 4-12 per second, each a 3-25 ms burst through a resonant
 *    band-pass between 900 Hz and 5 kHz. The resonance is essential: a
 *    crackle is a small cavity failing, and the cavity has a pitch. One in
 *    fifteen is a "pop" at more than three times the level, which is the
 *    single detail that makes people describe the sound as a real fire.
 *
 * The crackle rate itself drifts slowly between 4 and 12 per second so the
 * fire flares and settles over minutes.
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

    private val crackleClock = PoissonClock(sampleRate, crackleRng, preset.cracklesPerSecondMin)
    private val crackles = ResonantBurstVoicePool(preset.voices, crackleRng, sampleRate)
    private val rateDrift = SmoothNoise(
        (sampleRate / CONTROL_PERIOD).coerceAtLeast(1),
        rng,
        RATE_DRIFT_HZ,
    )
    private var controlCountdown = 0

    /** Crackles started since construction / [reset]; used by tests. */
    val crackleCount: Long get() = crackles.spawnCount

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
        val crackleLevel = preset.crackleLevel
        val minDecay = preset.crackleMinMs * sampleRate / 1000f
        val maxDecay = preset.crackleMaxMs * sampleRate / 1000f

        for (i in 0 until frames) {
            if (--controlCountdown <= 0) {
                controlCountdown = CONTROL_PERIOD
                val t = (rateDrift.next() + 1f) * 0.5f
                crackleClock.setRate(
                    preset.cracklesPerSecondMin +
                        (preset.cracklesPerSecondMax - preset.cracklesPerSecondMin) * t,
                )
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

            if (crackleClock.tick()) {
                val u = crackleRng.nextUnit()
                var amplitude = crackleLevel * (0.2f + 0.8f * u * u)
                if (crackleRng.nextBoolean(preset.popProbability)) amplitude *= preset.popGain
                val decay = crackleRng.nextRange(minDecay, maxDecay).toInt()
                val centre = crackleRng.nextRange(preset.crackleMinHz, preset.crackleMaxHz)
                val q = crackleRng.nextRange(preset.crackleQMin, preset.crackleQMax)
                crackles.spawn(decay, amplitude, crackleRng.nextFloat(), centre, q)
            }
            crackles.addSample(left, right, i)

            left[i] *= gain
            right[i] *= gain
        }
    }

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
        // left it somewhere else, and the first gap after a reset is drawn
        // from whatever rate is current, which would make reset(seed)
        // non-reproducible.
        crackleClock.setRate(preset.cracklesPerSecondMin)
        crackleClock.reset()
        crackles.reset()
        rateDrift.reset()
        controlCountdown = 0
    }

    private companion object {
        const val HISS_Q = 0.707f
        const val CONTROL_PERIOD = 64
        const val RATE_DRIFT_HZ = 0.05f
        const val RUMBLE_FLUTTER_DEPTH = 0.30f
        const val HISS_FLUTTER_DEPTH = 0.55f
    }
}
