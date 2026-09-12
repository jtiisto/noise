package dev.jtiisto.noise.core.audio.dsp

import dev.jtiisto.noise.core.audio.testing.SignalAnalysis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Unit tests for the DSP building blocks.
 *
 * Each one checks the property the rest of the engine depends on, not the
 * implementation: the RNG's range and distribution, a filter's measured
 * magnitude response, an envelope's endpoints, a voice pool's bookkeeping.
 */
class DspPrimitivesTest {

    @Nested
    inner class Rng {

        @Test
        @DisplayName("nextFloat stays in [-1, 1) with the right mean and RMS")
        fun uniformRange() {
            val rng = NoiseRng(1L)
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var sum = 0.0
            var sumSquares = 0.0
            val n = 1_000_000
            repeat(n) {
                val v = rng.nextFloat()
                if (v < min) min = v
                if (v > max) max = v
                sum += v
                sumSquares += v.toDouble() * v
            }
            assertTrue(min >= -1f, "min $min below -1")
            assertTrue(max < 1f, "max $max reached or passed 1")
            assertEquals(0.0, sum / n, 0.005, "mean is not zero")
            assertEquals(1.0 / sqrt(3.0), sqrt(sumSquares / n), 0.005, "RMS is not 1/sqrt(3)")
        }

        @Test
        @DisplayName("nextUnit stays in [0, 1)")
        fun unitRange() {
            val rng = NoiseRng(7L)
            repeat(200_000) {
                val v = rng.nextUnit()
                assertTrue(v >= 0f && v < 1f, "nextUnit produced $v")
            }
        }

        @Test
        @DisplayName("the same seed reproduces the same stream, different seeds do not")
        fun seeding() {
            val a = NoiseRng(99L)
            val b = NoiseRng(99L)
            repeat(1_000) { assertEquals(a.nextLong(), b.nextLong()) }

            val c = NoiseRng(99L)
            val d = NoiseRng(100L)
            var differences = 0
            repeat(1_000) { if (c.nextLong() != d.nextLong()) differences++ }
            assertTrue(differences > 990, "seeds 99 and 100 produced correlated streams")

            // reseed() must be equivalent to constructing a fresh instance.
            val reseeded = NoiseRng(1L)
            reseeded.reseed(99L)
            val fresh = NoiseRng(99L)
            repeat(100) { assertEquals(fresh.nextLong(), reseeded.nextLong()) }
        }

        @Test
        @DisplayName("a zero seed still produces a live stream")
        fun zeroSeedIsSafe() {
            val rng = NoiseRng(0L)
            val values = LongArray(100) { rng.nextLong() }
            assertTrue(values.toSet().size > 90, "the zero seed collapsed the state")
        }

        @Test
        @DisplayName("nextGaussian has unit variance and is bounded to +/-3")
        fun gaussian() {
            val rng = NoiseRng(5L)
            var sumSquares = 0.0
            val n = 500_000
            repeat(n) {
                val v = rng.nextGaussian()
                assertTrue(abs(v) <= 3f, "nextGaussian produced $v")
                sumSquares += v.toDouble() * v
            }
            assertEquals(1.0, sumSquares / n, 0.02)
        }

        @Test
        @DisplayName("nextExponential has mean 1 and is never negative")
        fun exponential() {
            val rng = NoiseRng(11L)
            var sum = 0.0
            val n = 500_000
            repeat(n) {
                val v = rng.nextExponential()
                assertTrue(v >= 0f)
                sum += v.toDouble()
            }
            assertEquals(1.0, sum / n, 0.02)
        }

        @Test
        @DisplayName("bounded helpers respect their bounds")
        fun boundedHelpers() {
            val rng = NoiseRng(13L)
            repeat(50_000) {
                assertTrue(rng.nextInt(7) in 0..6)
                assertTrue(rng.nextRange(2f, 5f) in 2f..5f)
            }
            var trues = 0
            repeat(100_000) { if (rng.nextBoolean(0.25f)) trues++ }
            assertEquals(0.25, trues / 100_000.0, 0.01)
        }
    }

    @Nested
    inner class Filters {

        @Test
        @DisplayName("the biquad's measured magnitude matches its design")
        fun biquadResponses() {
            val lowPass = Biquad().apply { setLowPass(SR, 1_000f, 0.707f) }
            assertEquals(0.0, db(lowPass.magnitudeAt(SR, 100f)), 0.5)
            assertEquals(-3.0, db(lowPass.magnitudeAt(SR, 1_000f)), 0.5)
            assertTrue(db(lowPass.magnitudeAt(SR, 8_000f)) < -30.0)

            val highPass = Biquad().apply { setHighPass(SR, 1_000f, 0.707f) }
            assertEquals(-3.0, db(highPass.magnitudeAt(SR, 1_000f)), 0.5)
            assertTrue(db(highPass.magnitudeAt(SR, 100f)) < -30.0)

            val bandPass = Biquad().apply { setBandPass(SR, 1_000f, 4f) }
            assertEquals(0.0, db(bandPass.magnitudeAt(SR, 1_000f)), 0.3)
            assertTrue(db(bandPass.magnitudeAt(SR, 250f)) < -15.0)
            assertTrue(db(bandPass.magnitudeAt(SR, 4_000f)) < -15.0)

            val peaking = Biquad().apply { setPeaking(SR, 500f, 1f, 6f) }
            assertEquals(6.0, db(peaking.magnitudeAt(SR, 500f)), 0.2)
            assertEquals(0.0, db(peaking.magnitudeAt(SR, 30f)), 0.5)

            val lowShelf = Biquad().apply { setLowShelf(SR, 200f, 10f) }
            assertEquals(10.0, db(lowShelf.magnitudeAt(SR, 20f)), 0.5)
            assertEquals(0.0, db(lowShelf.magnitudeAt(SR, 8_000f)), 0.5)

            val highShelf = Biquad().apply { setHighShelf(SR, 4_000f, 8f) }
            assertEquals(8.0, db(highShelf.magnitudeAt(SR, 20_000f)), 0.6)
            assertEquals(0.0, db(highShelf.magnitudeAt(SR, 100f)), 0.5)

            val notch = Biquad().apply { setNotch(SR, 1_000f, 8f) }
            assertTrue(db(notch.magnitudeAt(SR, 1_000f)) < -20.0)

            val identity = Biquad().apply { setIdentity() }
            assertEquals(0.5f, identity.process(0.5f))
        }

        @Test
        @DisplayName("the biquad is stable at extreme settings")
        fun biquadStability() {
            val rng = NoiseRng(3L)
            for (freq in floatArrayOf(1f, 20f, 1_000f, 23_000f, 40_000f)) {
                for (q in floatArrayOf(0.01f, 0.707f, 20f)) {
                    val filter = Biquad()
                    filter.setBandPass(SR, freq, q)
                    var worst = 0f
                    repeat(20_000) {
                        val v = filter.process(rng.nextFloat())
                        assertTrue(v.isFinite(), "biquad blew up at $freq Hz Q=$q")
                        if (abs(v) > worst) worst = abs(v)
                    }
                    assertTrue(worst < 100f, "biquad rang out of control at $freq Hz Q=$q")
                }
            }
        }

        @Test
        @DisplayName("retuning a biquad does not clear its state")
        fun biquadRetuneKeepsState() {
            val filter = Biquad()
            filter.setLowPass(SR, 500f, 0.707f)
            repeat(100) { filter.process(1f) }
            val before = filter.process(1f)
            filter.setLowPass(SR, 520f, 0.707f)
            val after = filter.process(1f)
            assertEquals(before.toDouble(), after.toDouble(), 0.05)
        }

        @Test
        @DisplayName("the one-pole low-pass attenuates by the predicted RMS gain")
        fun onePoleGain() {
            val rng = NoiseRng(17L)
            val filter = OnePoleLowPass(SR, 500f)
            val n = 400_000
            var sumSquares = 0.0
            repeat(n) {
                val v = filter.process(rng.nextFloat())
                sumSquares += v.toDouble() * v
            }
            val measured = sqrt(sumSquares / n)
            val predicted = OnePoleLowPass.whiteRmsGain(filter.coefficient) / sqrt(3.0)
            assertEquals(predicted, measured, predicted * 0.05)
        }

        @Test
        @DisplayName("the one-pole high-pass removes DC")
        fun onePoleHighPassKillsDc() {
            val filter = OnePoleHighPass(SR, 25f)
            var last = 0f
            repeat(SR) { last = filter.process(1f) }
            assertTrue(abs(last) < 0.01f, "DC survived the high-pass at $last")
        }

        @Test
        @DisplayName("forceTo settles the one-pole immediately")
        fun onePoleForceTo() {
            val filter = OnePoleLowPass(SR, 0.1f)
            filter.forceTo(0.75f)
            assertEquals(0.75, filter.value.toDouble(), 1e-6)
            assertEquals(0.75, filter.process(0.75f).toDouble(), 1e-6)
            filter.reset()
            assertEquals(0.0, filter.value.toDouble(), 1e-9)
        }

        @Test
        @DisplayName("the leaky integrator's RMS gain matches its closed form")
        fun leakyIntegratorGain() {
            val rng = NoiseRng(19L)
            val integrator = LeakyIntegrator(SR, 20f)
            val n = 600_000
            var sumSquares = 0.0
            repeat(n) {
                val v = integrator.process(rng.nextFloat())
                sumSquares += v.toDouble() * v
            }
            val measured = sqrt(sumSquares / n)
            val predicted = integrator.whiteRmsGain / sqrt(3.0)
            assertEquals(predicted, measured, predicted * 0.08)
        }

        @Test
        @DisplayName("brown noise comes out at unit RMS with a -6 dB/oct slope")
        fun brownSourceIsUnitRms() {
            val rng = NoiseRng(23L)
            val source = BrownNoiseSource(SR, rng)
            val samples = FloatArray(SR * 15) { source.next() }
            assertEquals(1.0, SignalAnalysis.rms(samples), 0.06)
            val psd = SignalAnalysis.welchPsd(samples, 8192)
            assertEquals(-6.0, SignalAnalysis.spectralSlopeDbPerOctave(psd, SR), 0.6)
            source.reset()
        }

        @Test
        @DisplayName("the pink filter delivers -3 dB/oct")
        fun pinkFilterSlope() {
            val rng = NoiseRng(29L)
            val filter = PinkFilter()
            val samples = FloatArray(SR * 15) { filter.process(rng.nextFloat()) }
            val psd = SignalAnalysis.welchPsd(samples, 8192)
            assertEquals(-3.0, SignalAnalysis.spectralSlopeDbPerOctave(psd, SR), 0.5)
            filter.reset()
            assertEquals(0.0, filter.process(0f).toDouble(), 1e-9)
        }

        @Test
        @DisplayName("band-pass makeup gain restores unit RMS whatever Q and centre")
        fun bandPassMakeup() {
            val rng = NoiseRng(31L)
            for (centre in floatArrayOf(400f, 1_500f, 5_000f)) {
                for (q in floatArrayOf(4f, 10f)) {
                    val filter = Biquad().apply { setBandPass(SR, centre, q) }
                    val makeup = bandPassNoiseMakeup(centre, q, SR)
                    val n = 400_000
                    var sumSquares = 0.0
                    repeat(n) {
                        val v = filter.process(rng.nextFloat() * sqrt(3f)) * makeup
                        sumSquares += v.toDouble() * v
                    }
                    val measured = sqrt(sumSquares / n)
                    assertEquals(1.0, measured, 0.15, "centre $centre Q $q gave RMS $measured")
                }
            }
        }
    }

    @Nested
    inner class Modulators {

        @Test
        @DisplayName("the sine table matches Math.sin closely")
        fun fastSineAccuracy() {
            var worst = 0.0
            var p = 0.0
            while (p < 1.0) {
                val error = abs(FastSine.at(p.toFloat()) - kotlin.math.sin(2 * Math.PI * p))
                if (error > worst) worst = error
                p += 1e-4
            }
            assertTrue(worst < 1e-4, "wavetable sine error $worst is audible")
            // Phase wrapping must work in both directions.
            assertEquals(FastSine.at(0.25f), FastSine.at(1.25f), 1e-6f)
            assertEquals(FastSine.at(0.25f), FastSine.at(-0.75f), 1e-6f)
        }

        @Test
        @DisplayName("the LFO runs at the frequency it is set to")
        fun lfoFrequency() {
            val lfo = SineLfo(SR, 10f)
            val samples = FloatArray(SR) { lfo.next() }
            val psd = SignalAnalysis.welchPsd(samples, 8192)
            assertEquals(10.0, SignalAnalysis.dominantFrequency(psd, SR, minHz = 1.0), 4.0)
            lfo.setFrequency(50f)
            lfo.setPhase(0f)
            lfo.reset()
            assertEquals(0.0, lfo.next().toDouble(), 1e-6)
        }

        @Test
        @DisplayName("smoothed noise stays inside [-1, 1] and is slow")
        fun smoothNoiseIsBoundedAndSlow() {
            val rng = NoiseRng(37L)
            val noise = SmoothNoise(SR, rng, 0.2f)
            var previous = noise.next()
            var worstStep = 0f
            repeat(SR * 20) {
                val v = noise.next()
                assertTrue(v in -1f..1f, "smoothed noise escaped to $v")
                val step = abs(v - previous)
                if (step > worstStep) worstStep = step
                previous = v
            }
            assertTrue(worstStep < 0.01f, "a 0.2 Hz control signal stepped by $worstStep")
            assertEquals(previous, noise.value)
            noise.reset()
        }

        @Test
        @DisplayName("the random walk respects its range and reaches both ends")
        fun randomWalkRange() {
            val rng = NoiseRng(41L)
            val walk = RandomWalk(1_000, rng, 200f, 800f, 2f)
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            repeat(200_000) {
                val v = walk.next()
                assertTrue(v in 200f..800f, "walk escaped to $v")
                if (v < min) min = v
                if (v > max) max = v
            }
            assertTrue(max - min > 300f, "the walk only covered ${max - min} Hz of its 600 Hz range")
            walk.setRange(0f, 1f)
            walk.setRate(1f)
            walk.reset()
            repeat(1_000) { assertTrue(walk.next() in 0f..1f) }
            assertTrue(walk.value in 0f..1f)
        }

        @Test
        @DisplayName("the Poisson clock fires at the configured mean rate")
        fun poissonRate() {
            val rng = NoiseRng(43L)
            val clock = PoissonClock(SR, rng, 25f)
            var events = 0
            repeat(SR * 40) { if (clock.tick()) events++ }
            assertEquals(25.0, events / 40.0, 25.0 * 0.1)
        }

        @Test
        @DisplayName("a Poisson clock at rate zero never fires, and scheduleUniform is honoured")
        fun poissonEdgeCases() {
            val rng = NoiseRng(47L)
            val clock = PoissonClock(SR, rng, 0f)
            var events = 0
            repeat(SR) { if (clock.tick()) events++ }
            assertEquals(0, events)

            clock.setRate(4f)
            clock.scheduleUniform(1f, 1f)
            var samples = 0
            while (!clock.tick()) samples++
            assertEquals(SR.toDouble(), (samples + 1).toDouble(), SR * 0.01)
            clock.reset()
        }
    }

    @Nested
    inner class Envelopes {

        @Test
        @DisplayName("the equal-power fade hits exactly 0 and 1 and is monotonic")
        fun equalPowerEndpoints() {
            assertEquals(0f, EqualPowerFade.value(0f))
            assertEquals(1f, EqualPowerFade.value(1f))
            assertEquals(0f, EqualPowerFade.value(-0.5f))
            assertEquals(1f, EqualPowerFade.value(2f))
            assertEquals(0.5, EqualPowerFade.value(0.5f).toDouble(), 1e-4)
            var previous = -1f
            var t = 0f
            while (t <= 1f) {
                val v = EqualPowerFade.value(t)
                assertTrue(v >= previous - 1e-6f, "fade dipped at t=$t")
                previous = v
                t += 1e-4f
            }
        }

        @Test
        @DisplayName("the fade curve matches sin^2 to table precision")
        fun equalPowerShape() {
            var worst = 0.0
            var t = 0.0
            while (t <= 1.0) {
                val expected = kotlin.math.sin(t * Math.PI / 2).let { it * it }
                worst = maxOf(worst, abs(EqualPowerFade.value(t.toFloat()) - expected))
                t += 1e-4
            }
            assertTrue(worst < 1e-5, "fade table error $worst")
        }

        @Test
        @DisplayName("the linear ramp reaches its target in exactly the requested samples")
        fun linearRampTiming() {
            val ramp = LinearRamp(0f)
            ramp.rampTo(1f, 100)
            repeat(99) { ramp.next() }
            assertTrue(!ramp.isAtTarget, "the ramp finished early")
            ramp.next()
            assertTrue(ramp.isAtTarget, "the ramp did not finish on time")
            assertEquals(1f, ramp.value)
            // Ramping to the value it already holds must be a no-op, not a jump.
            ramp.rampTo(1f, 50)
            assertEquals(1f, ramp.next())
            ramp.jumpTo(0.25f)
            assertEquals(0.25f, ramp.value)
            assertTrue(ramp.isAtTarget)
            ramp.rampTo(0f, 0)
            assertEquals(0f, ramp.next(), "a zero-length ramp should complete in one sample")
        }

        @Test
        @DisplayName("the dual-exponential peak matches a numerical search")
        fun dualExponentialPeak() {
            for (ratio in floatArrayOf(2f, 8f, 40f, 200f)) {
                val analytic = DualExponential.peak(ratio)
                var numeric = 0.0
                var t = 0.0
                while (t < 20.0) {
                    val v = kotlin.math.exp(-t) - kotlin.math.exp(-t * ratio)
                    if (v > numeric) numeric = v
                    t += 1e-4
                }
                assertEquals(numeric, analytic.toDouble(), 1e-3, "ratio $ratio")
            }
            // Degenerate ratios must not divide by zero.
            assertTrue(DualExponential.peak(1f) >= 0f)
        }

        @Test
        @DisplayName("the decay coefficient reaches -40 dB in the requested time")
        fun decayCoefficient() {
            val coefficient = DualExponential.decayCoefficient(1_000f)
            var value = 1f
            repeat(1_000) { value *= coefficient }
            assertEquals(0.01, value.toDouble(), 0.001)
        }
    }

    @Nested
    inner class VoicePools {

        @Test
        @DisplayName("noise-burst voices sound, decay and are returned to the pool")
        fun noiseBurstLifecycle() {
            val rng = NoiseRng(53L)
            val pool = NoiseBurstVoicePool(4, rng, SR)
            val left = FloatArray(SR)
            val right = FloatArray(SR)
            pool.spawn(durationSamples = 480, peakAmplitude = 0.5f, pan = 0f, cutoffHz = 4_000f)
            assertEquals(1, pool.activeCount)
            for (i in 0 until SR) pool.addSample(left, right, i)
            assertEquals(0, pool.activeCount, "the voice was never released")
            assertEquals(1L, pool.spawnCount)
            assertTrue(SignalAnalysis.peak(left) > 0.05f, "the voice produced no sound")
            // The envelope must start at zero, not click in.
            assertEquals(0f, left[0], 1e-6f)
            assertTrue(SignalAnalysis.allFinite(left))
        }

        @Test
        @DisplayName("panning routes a voice to the right side")
        fun panning() {
            val rng = NoiseRng(59L)
            val pool = NoiseBurstVoicePool(2, rng, SR)
            val left = FloatArray(4_800)
            val right = FloatArray(4_800)
            pool.spawn(480, 0.5f, pan = 1f, cutoffHz = 4_000f)
            for (i in left.indices) pool.addSample(left, right, i)
            assertTrue(SignalAnalysis.peak(right) > 0.05f)
            assertEquals(0f, SignalAnalysis.peak(left), "a hard-right voice leaked to the left")
        }

        @Test
        @DisplayName("an over-subscribed pool steals rather than dropping or growing")
        fun voiceStealing() {
            val rng = NoiseRng(61L)
            val pool = NoiseBurstVoicePool(3, rng, SR)
            val left = FloatArray(64)
            val right = FloatArray(64)
            repeat(20) {
                pool.spawn(48_000, 0.2f, 0f, 3_000f)
                for (i in left.indices) pool.addSample(left, right, i)
            }
            assertEquals(20L, pool.spawnCount)
            assertTrue(pool.activeCount <= 3, "the pool grew past its capacity")
            assertTrue(SignalAnalysis.allFinite(left))
        }

        @Test
        @DisplayName("reset empties the pool and clears its counters")
        fun poolReset() {
            val rng = NoiseRng(67L)
            val pool = ResonantBurstVoicePool(4, rng, SR)
            pool.spawn(4_800, 0.5f, 0f, 2_000f, 8f)
            assertNotEquals(0, pool.activeCount)
            pool.reset()
            assertEquals(0, pool.activeCount)
            assertEquals(0L, pool.spawnCount)
        }

        @Test
        @DisplayName("resonant voices are centred on the frequency they were given")
        fun resonantVoiceTuning() {
            val rng = NoiseRng(71L)
            val pool = ResonantBurstVoicePool(8, rng, SR)
            val left = FloatArray(SR * 2)
            val right = FloatArray(SR * 2)
            // A dense stream of long voices at one pitch, so the average
            // spectrum has somewhere to converge.
            var i = 0
            while (i < left.size) {
                if (i % 2_400 == 0) pool.spawn(4_800, 0.4f, 0f, 2_000f, 10f)
                pool.addSample(left, right, i)
                i++
            }
            val psd = SignalAnalysis.welchPsd(left, 4096)
            assertEquals(2_000.0, SignalAnalysis.dominantFrequency(psd, SR, 100.0), 250.0)
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        @DisplayName("default constructor arguments produce working primitives")
        fun defaultsAreUsable() {
            val rng = NoiseRng(3L)
            assertTrue(SmoothNoise(SR, rng).next() in -1f..1f)
            assertTrue(RandomWalk(SR, rng, 0f, 1f).next() in 0f..1f)
            val clock = PoissonClock(SR, rng)
            var events = 0
            repeat(SR * 4) { if (clock.tick()) events++ }
            assertTrue(events in 1..12, "the default 1 Hz clock fired $events times in 4 s")
            assertTrue(OnePoleLowPass(SR).process(1f) > 0f)
            assertTrue(LeakyIntegrator(SR).process(1f) > 0f)
            val highPass = OnePoleHighPass(SR)
            highPass.setCutoff(100f)
            highPass.reset()
            // A one-pole high-pass passes a step almost intact: the leaked
            // fraction is exactly the low-pass coefficient for its corner.
            assertEquals(1f, highPass.process(1f), 0.02f, "a fresh high-pass should pass a step")
        }

        @Test
        @DisplayName("an implausibly fast Poisson clock still fires at most once per sample")
        fun poissonClampsToOneSample() {
            val rng = NoiseRng(97L)
            val clock = PoissonClock(SR, rng, 1_000_000f)
            var events = 0
            repeat(1_000) { if (clock.tick()) events++ }
            assertEquals(1_000, events, "a saturated clock must fire every sample, not skip")
        }

        @Test
        @DisplayName("a zero-length voice is clamped rather than dividing by zero")
        fun zeroLengthVoice() {
            val rng = NoiseRng(101L)
            val pool = NoiseBurstVoicePool(2, rng, SR)
            pool.spawn(durationSamples = 0, peakAmplitude = 0.5f, pan = -1f, cutoffHz = 1_000f)
            val left = FloatArray(512)
            val right = FloatArray(512)
            for (i in left.indices) pool.addSample(left, right, i)
            assertTrue(SignalAnalysis.allFinite(left))
            assertEquals(0, pool.activeCount)
        }

        @Test
        @DisplayName("filters clamp out-of-range cutoffs instead of going unstable")
        fun cutoffsAreClamped() {
            val lp = OnePoleLowPass(SR, -100f)
            assertTrue(lp.process(1f).isFinite())
            lp.setCutoff(1e9f)
            assertTrue(lp.process(1f).isFinite())
            val integrator = LeakyIntegrator(SR, 0f)
            assertTrue(integrator.process(1f).isFinite())
            val biquad = Biquad()
            biquad.setLowPass(SR, 0f, 0f)
            assertTrue(biquad.process(1f).isFinite())
            assertTrue(biquad.magnitudeAt(SR, 1_000f).isFinite())
        }
    }

    private companion object {
        const val SR = 48_000

        fun db(magnitude: Double) = 20.0 * log10(magnitude.coerceAtLeast(1e-12))
    }
}
