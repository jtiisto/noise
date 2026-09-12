package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.SoundGenerator
import dev.jtiisto.noise.core.audio.dsp.Biquad
import dev.jtiisto.noise.core.audio.dsp.BrownNoiseSource
import dev.jtiisto.noise.core.audio.dsp.FastSine
import dev.jtiisto.noise.core.audio.dsp.NoiseRng
import dev.jtiisto.noise.core.audio.dsp.OnePoleLowPass
import dev.jtiisto.noise.core.audio.dsp.PinkFilter
import dev.jtiisto.noise.core.audio.dsp.SineLfo

/**
 * @param bladeHz rotation-times-blades frequency of the AM. 28 Hz is a box fan
 *        on medium; the modulation is only 4 % deep because a deeper one reads
 *        as a broken fan rather than a working one.
 * @param humLevel level of the motor's mains hum. -30 dB: present enough to
 *        say "motor", quiet enough not to be a tone you notice.
 */
data class FanPreset(
    val lowPassHz: Float = 1_800f,
    val resonanceHz: Float = 400f,
    val resonanceQ: Float = 0.7f,
    val resonanceDb: Float = 4f,
    val bladeHz: Float = 28f,
    val amDepth: Float = 0.04f,
    val humHz: Float = 110f,
    val humLevel: Float = 0.0316f,
    val outputGain: Float = 0.0585f,
) {
    companion object {
        val DEFAULT = FanPreset()
    }
}

/**
 * Fan — pink noise shaped into a box fan.
 *
 * The chain is deliberately short: a broad +4 dB resonance at 400 Hz (the
 * housing), a 1.8 kHz low-pass (blade noise has no top end), a 4 % amplitude
 * modulation at the blade rate, and a quiet 110 Hz hum for the motor.
 *
 * The blade AM is the only part that must be exact. Too deep and it becomes a
 * helicopter; absent and the sound is indistinguishable from filtered pink
 * noise. 4 % at 28 Hz is right at the edge of conscious perception, which is
 * exactly where a machine bed should sit — the brain classifies it as a real
 * object and then ignores it.
 *
 * The hum is the one mono element (a single motor), which is realistic and, at
 * -30 dB, far too quiet to collapse the stereo image.
 */
class FanGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: FanPreset = FanPreset.DEFAULT,
) : SoundGenerator {

    private val rngLeft = NoiseRng(streamSeed(seed, 101))
    private val rngRight = NoiseRng(streamSeed(seed, 102))
    private val pinkLeft = PinkFilter()
    private val pinkRight = PinkFilter()
    private val resonanceLeft = Biquad()
    private val resonanceRight = Biquad()
    private val lowPassLeft = OnePoleLowPass(sampleRate, preset.lowPassHz)
    private val lowPassRight = OnePoleLowPass(sampleRate, preset.lowPassHz)
    private val blade = SineLfo(sampleRate, preset.bladeHz)
    private val hum = SineLfo(sampleRate, preset.humHz)

    init {
        resonanceLeft.setPeaking(sampleRate, preset.resonanceHz, preset.resonanceQ, preset.resonanceDb)
        resonanceRight.setPeaking(sampleRate, preset.resonanceHz, preset.resonanceQ, preset.resonanceDb)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain
        val depth = preset.amDepth
        val humLevel = preset.humLevel

        for (i in 0 until frames) {
            val am = 1f + depth * blade.next()
            val h = hum.next() * humLevel
            val l = lowPassLeft.process(resonanceLeft.process(pinkLeft.process(rngLeft.nextFloat())))
            val r =
                lowPassRight.process(resonanceRight.process(pinkRight.process(rngRight.nextFloat())))
            left[i] = (l * am + h) * gain
            right[i] = (r * am + h) * gain
        }
    }

    override fun reset(seed: Long) {
        rngLeft.reseed(streamSeed(seed, 101))
        rngRight.reseed(streamSeed(seed, 102))
        pinkLeft.reset()
        pinkRight.reset()
        resonanceLeft.reset()
        resonanceRight.reset()
        lowPassLeft.reset()
        lowPassRight.reset()
        blade.reset()
        hum.reset()
    }
}

/**
 * @param driftHz how fast the cabin "breathes". 0.02 Hz is a 50 s cycle — slow
 *        enough that it is never heard as modulation, only as the sound not
 *        being frozen.
 */
data class AirplanePreset(
    val lowPassHz: Float = 900f,
    val peakHz: Float = 180f,
    val peakQ: Float = 1.2f,
    val peakDb: Float = 6f,
    val ventLowHz: Float = 2_000f,
    val ventHighHz: Float = 4_000f,
    /** -18 dB under the rumble, per the spec. */
    val ventLevel: Float = 0.126f,
    val driftHz: Float = 0.02f,
    val driftDb: Float = 1f,
    val outputGain: Float = 0.0828f,
) {
    companion object {
        val DEFAULT = AirplanePreset()
    }
}

/**
 * Airplane cabin — a brown rumble with a resonant peak plus vent hiss.
 *
 * Two components, because that is what a cabin actually is: engine and
 * boundary-layer noise conducted through the fuselage (brown, low-passed at
 * 900 Hz, with a +6 dB bump at 180 Hz where the tube resonates) and the
 * air-conditioning outlet (a narrow 2-4 kHz hiss, 18 dB down).
 *
 * The 0.02 Hz +/-1 dB drift matters more than it looks. A perfectly steady bed
 * is the one thing that reliably reads as synthetic over a long night; a 50 s
 * breathing cycle is below the threshold at which anyone notices modulation
 * but above the threshold at which the sound feels dead.
 */
class AirplaneGenerator(
    private val sampleRate: Int,
    seed: Long,
    private val preset: AirplanePreset = AirplanePreset.DEFAULT,
) : SoundGenerator {

    private val rumbleRngLeft = NoiseRng(streamSeed(seed, 111))
    private val rumbleRngRight = NoiseRng(streamSeed(seed, 112))
    private val ventRngLeft = NoiseRng(streamSeed(seed, 113))
    private val ventRngRight = NoiseRng(streamSeed(seed, 114))

    private val brownLeft = BrownNoiseSource(sampleRate, rumbleRngLeft)
    private val brownRight = BrownNoiseSource(sampleRate, rumbleRngRight)
    private val peakLeft = Biquad()
    private val peakRight = Biquad()
    private val lowPassLeft = OnePoleLowPass(sampleRate, preset.lowPassHz)
    private val lowPassRight = OnePoleLowPass(sampleRate, preset.lowPassHz)
    private val ventHighPassLeft = Biquad()
    private val ventHighPassRight = Biquad()
    private val ventLowPassLeft = Biquad()
    private val ventLowPassRight = Biquad()

    private var driftPhase = 0f
    private val driftIncrement = preset.driftHz / sampleRate
    private val driftAmplitude = (Math.pow(10.0, preset.driftDb / 20.0) - 1.0).toFloat()

    init {
        peakLeft.setPeaking(sampleRate, preset.peakHz, preset.peakQ, preset.peakDb)
        peakRight.setPeaking(sampleRate, preset.peakHz, preset.peakQ, preset.peakDb)
        ventHighPassLeft.setHighPass(sampleRate, preset.ventLowHz, VENT_Q)
        ventHighPassRight.setHighPass(sampleRate, preset.ventLowHz, VENT_Q)
        ventLowPassLeft.setLowPass(sampleRate, preset.ventHighHz, VENT_Q)
        ventLowPassRight.setLowPass(sampleRate, preset.ventHighHz, VENT_Q)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val gain = preset.outputGain
        val ventLevel = preset.ventLevel

        for (i in 0 until frames) {
            val drift = 1f + driftAmplitude * FastSine.at(driftPhase)
            driftPhase += driftIncrement
            if (driftPhase >= 1f) driftPhase -= 1f

            var l = lowPassLeft.process(peakLeft.process(brownLeft.next()))
            var r = lowPassRight.process(peakRight.process(brownRight.next()))
            l += ventLowPassLeft.process(ventHighPassLeft.process(ventRngLeft.nextFloat())) *
                ventLevel
            r += ventLowPassRight.process(ventHighPassRight.process(ventRngRight.nextFloat())) *
                ventLevel

            left[i] = l * drift * gain
            right[i] = r * drift * gain
        }
    }

    override fun reset(seed: Long) {
        rumbleRngLeft.reseed(streamSeed(seed, 111))
        rumbleRngRight.reseed(streamSeed(seed, 112))
        ventRngLeft.reseed(streamSeed(seed, 113))
        ventRngRight.reseed(streamSeed(seed, 114))
        brownLeft.reset()
        brownRight.reset()
        peakLeft.reset()
        peakRight.reset()
        lowPassLeft.reset()
        lowPassRight.reset()
        ventHighPassLeft.reset()
        ventHighPassRight.reset()
        ventLowPassLeft.reset()
        ventLowPassRight.reset()
        driftPhase = 0f
    }

    private companion object {
        const val VENT_Q = 0.707f
    }
}
