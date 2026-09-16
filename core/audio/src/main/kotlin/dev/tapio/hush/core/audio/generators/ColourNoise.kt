package dev.tapio.hush.core.audio.generators

import dev.tapio.hush.core.audio.SoundGenerator
import dev.tapio.hush.core.audio.dsp.Biquad
import dev.tapio.hush.core.audio.dsp.BrownNoiseSource
import dev.tapio.hush.core.audio.dsp.NoiseRng
import dev.tapio.hush.core.audio.dsp.OnePoleLowPass
import dev.tapio.hush.core.audio.dsp.PinkFilter

/**
 * The classic noise colours.
 *
 * All six share one structural decision: **left and right are separate
 * generators driven by separate RNG streams.** A single mono noise stream
 * duplicated to both ears images as a hard point inside the listener's head,
 * which is fatiguing over eight hours; two uncorrelated streams sound like a
 * wide, enveloping field. The cost is a second copy of the filter state, which
 * for a handful of poles is nothing.
 *
 * The calibration gains below are measured, not derived — see
 * [GeneratorSupport][TARGET_RMS].
 */

/**
 * White: uniform noise, flat power spectrum.
 *
 * Uniform rather than Gaussian on purpose. What "white" means is a flat
 * spectrum, and any zero-mean IID sequence has one; the uniform draw is a
 * single multiply where a Gaussian is three, and at these levels the amplitude
 * distribution is inaudible. (Where the distribution *does* matter — dense
 * event beds like rain — the generators call `nextGaussian` explicitly.)
 */
class WhiteNoiseGenerator(
    @Suppress("unused") private val sampleRate: Int,
    seed: Long,
) : SoundGenerator {

    private val rngLeft = NoiseRng(streamSeed(seed, 1))
    private val rngRight = NoiseRng(streamSeed(seed, 2))

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val g = GAIN
        val l = rngLeft
        val r = rngRight
        for (i in 0 until frames) {
            left[i] = l.nextFloat() * g
            right[i] = r.nextFloat() * g
        }
    }

    override fun reset(seed: Long) {
        rngLeft.reseed(streamSeed(seed, 1))
        rngRight.reseed(streamSeed(seed, 2))
    }

    private companion object {
        // Uniform [-1,1) has RMS 1/sqrt(3); 0.1 / 0.5774 = 0.1732.
        const val GAIN = 0.17320f
    }
}

/** Pink: -3 dB/oct via [PinkFilter]. The default "soft hiss" most people mean by white noise. */
class PinkNoiseGenerator(
    @Suppress("unused") private val sampleRate: Int,
    seed: Long,
) : SoundGenerator {

    private val rngLeft = NoiseRng(streamSeed(seed, 3))
    private val rngRight = NoiseRng(streamSeed(seed, 4))
    private val filterLeft = PinkFilter()
    private val filterRight = PinkFilter()

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val g = GAIN
        for (i in 0 until frames) {
            left[i] = filterLeft.process(rngLeft.nextFloat()) * g
            right[i] = filterRight.process(rngRight.nextFloat()) * g
        }
    }

    override fun reset(seed: Long) {
        rngLeft.reseed(streamSeed(seed, 3))
        rngRight.reseed(streamSeed(seed, 4))
        filterLeft.reset()
        filterRight.reset()
    }

    private companion object {
        const val GAIN = 0.05597f
    }
}

/**
 * Brown (Brownian / red): -6 dB/oct.
 *
 * A leaky integrator at 8 Hz turns white noise into a bounded random walk, and
 * a one-pole high-pass at 25 Hz throws away the sub-audio wander underneath.
 * Skipping that high-pass is the classic brown-noise bug: the DC term drifts,
 * eats headroom, and on a phone speaker just wastes excursion on inaudible
 * content while making the clipper work.
 */
class BrownNoiseGenerator(sampleRate: Int, seed: Long) : SoundGenerator {

    private val rngLeft = NoiseRng(streamSeed(seed, 5))
    private val rngRight = NoiseRng(streamSeed(seed, 6))
    private val brownLeft = BrownNoiseSource(sampleRate, rngLeft, INTEGRATOR_HZ, HIGH_PASS_HZ)
    private val brownRight = BrownNoiseSource(sampleRate, rngRight, INTEGRATOR_HZ, HIGH_PASS_HZ)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val g = GAIN
        for (i in 0 until frames) {
            left[i] = brownLeft.next() * g
            right[i] = brownRight.next() * g
        }
    }

    override fun reset(seed: Long) {
        rngLeft.reseed(streamSeed(seed, 5))
        rngRight.reseed(streamSeed(seed, 6))
        brownLeft.reset()
        brownRight.reset()
    }

    private companion object {
        const val INTEGRATOR_HZ = 8f
        const val HIGH_PASS_HZ = 25f
        // BrownNoiseSource is unit-RMS by construction, so this is the
        // target level times a small measured trim for the difference between
        // the continuous-time formulas and their discrete implementations.
        const val GAIN = TARGET_RMS * 0.984f
    }
}

/**
 * Blue: +3 dB/oct, the mirror of pink. Differentiating pink noise adds
 * +6 dB/oct to its -3, and a 16 kHz one-pole takes the last of the sizzle off
 * so it is airy rather than abrasive at 3 a.m.
 */
class BlueNoiseGenerator(sampleRate: Int, seed: Long) : SoundGenerator {

    private val rngLeft = NoiseRng(streamSeed(seed, 7))
    private val rngRight = NoiseRng(streamSeed(seed, 8))
    private val pinkLeft = PinkFilter()
    private val pinkRight = PinkFilter()
    private val tiltLeft = OnePoleLowPass(sampleRate, TAME_HZ)
    private val tiltRight = OnePoleLowPass(sampleRate, TAME_HZ)
    private var previousLeft = 0f
    private var previousRight = 0f

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val g = GAIN
        var pl = previousLeft
        var pr = previousRight
        for (i in 0 until frames) {
            val l = pinkLeft.process(rngLeft.nextFloat())
            val r = pinkRight.process(rngRight.nextFloat())
            left[i] = tiltLeft.process(l - pl) * g
            right[i] = tiltRight.process(r - pr) * g
            pl = l
            pr = r
        }
        previousLeft = pl
        previousRight = pr
    }

    override fun reset(seed: Long) {
        rngLeft.reseed(streamSeed(seed, 7))
        rngRight.reseed(streamSeed(seed, 8))
        pinkLeft.reset()
        pinkRight.reset()
        tiltLeft.reset()
        tiltRight.reset()
        previousLeft = 0f
        previousRight = 0f
    }

    private companion object {
        const val TAME_HZ = 16_000f
        const val GAIN = 0.11197f
    }
}

/** Violet (purple): +6 dB/oct — the first difference of white, with the same 16 kHz tilt. */
class VioletNoiseGenerator(sampleRate: Int, seed: Long) : SoundGenerator {

    private val rngLeft = NoiseRng(streamSeed(seed, 9))
    private val rngRight = NoiseRng(streamSeed(seed, 10))
    private val tiltLeft = OnePoleLowPass(sampleRate, TAME_HZ)
    private val tiltRight = OnePoleLowPass(sampleRate, TAME_HZ)
    private var previousLeft = 0f
    private var previousRight = 0f

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val g = GAIN
        var pl = previousLeft
        var pr = previousRight
        for (i in 0 until frames) {
            val l = rngLeft.nextFloat()
            val r = rngRight.nextFloat()
            left[i] = tiltLeft.process(l - pl) * g
            right[i] = tiltRight.process(r - pr) * g
            pl = l
            pr = r
        }
        previousLeft = pl
        previousRight = pr
    }

    override fun reset(seed: Long) {
        rngLeft.reseed(streamSeed(seed, 9))
        rngRight.reseed(streamSeed(seed, 10))
        tiltLeft.reset()
        tiltRight.reset()
        previousLeft = 0f
        previousRight = 0f
    }

    private companion object {
        const val TAME_HZ = 16_000f
        const val GAIN = 0.14823f
    }
}

/**
 * Grey: white noise shaped by an inverse-A-weighting approximation, so it
 * measures tilted but *sounds* flat — equally loud at every pitch.
 *
 * A-weighting rolls off below ~500 Hz and above ~6 kHz and peaks near 3 kHz,
 * so its inverse is a low shelf up, a dip in the presence region and a high
 * shelf up. Three biquads get within a couple of dB of the real curve, which
 * is well inside the spread between individual listeners' equal-loudness
 * contours; a 1024-tap FIR of the exact inverse would be honest overkill.
 */
class GreyNoiseGenerator(sampleRate: Int, seed: Long) : SoundGenerator {

    private val rngLeft = NoiseRng(streamSeed(seed, 11))
    private val rngRight = NoiseRng(streamSeed(seed, 12))
    private val shelfLowLeft = Biquad()
    private val shelfLowRight = Biquad()
    private val dipLeft = Biquad()
    private val dipRight = Biquad()
    private val shelfHighLeft = Biquad()
    private val shelfHighRight = Biquad()

    init {
        configure(sampleRate)
    }

    private fun configure(sampleRate: Int) {
        shelfLowLeft.setLowShelf(sampleRate, LOW_SHELF_HZ, LOW_SHELF_DB)
        shelfLowRight.setLowShelf(sampleRate, LOW_SHELF_HZ, LOW_SHELF_DB)
        dipLeft.setPeaking(sampleRate, DIP_HZ, DIP_Q, DIP_DB)
        dipRight.setPeaking(sampleRate, DIP_HZ, DIP_Q, DIP_DB)
        shelfHighLeft.setHighShelf(sampleRate, HIGH_SHELF_HZ, HIGH_SHELF_DB)
        shelfHighRight.setHighShelf(sampleRate, HIGH_SHELF_HZ, HIGH_SHELF_DB)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val g = GAIN
        for (i in 0 until frames) {
            var l = rngLeft.nextFloat()
            l = shelfLowLeft.process(l)
            l = dipLeft.process(l)
            left[i] = shelfHighLeft.process(l) * g
            var r = rngRight.nextFloat()
            r = shelfLowRight.process(r)
            r = dipRight.process(r)
            right[i] = shelfHighRight.process(r) * g
        }
    }

    override fun reset(seed: Long) {
        rngLeft.reseed(streamSeed(seed, 11))
        rngRight.reseed(streamSeed(seed, 12))
        shelfLowLeft.reset()
        shelfLowRight.reset()
        dipLeft.reset()
        dipRight.reset()
        shelfHighLeft.reset()
        shelfHighRight.reset()
    }

    private companion object {
        const val LOW_SHELF_HZ = 120f
        const val LOW_SHELF_DB = 12f
        const val DIP_HZ = 3_000f
        const val DIP_Q = 1.0f
        const val DIP_DB = -4f
        const val HIGH_SHELF_HZ = 10_000f
        const val HIGH_SHELF_DB = 6f
        const val GAIN = 0.10994f
    }
}
