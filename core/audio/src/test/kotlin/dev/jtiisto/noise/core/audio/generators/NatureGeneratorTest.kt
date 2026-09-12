package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.testing.RenderHarness
import dev.jtiisto.noise.core.audio.testing.SignalAnalysis
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Behavioural assertions for the event-driven nature sounds: not "does it make
 * noise" but "does it make the *right kind* of noise" — drops arriving at the
 * modelled rate, swells with the modelled period and depth, chirps at the
 * modelled pitch.
 *
 * These are the tests that catch a synthesis regression that a level check
 * would sail past.
 */
class NatureGeneratorTest {

    @Test
    @DisplayName("rain spawns drops at the preset's Poisson rate")
    fun rainDropRateMatchesPreset() {
        for (preset in listOf(RainPreset.LIGHT, RainPreset.DOWNPOUR)) {
            val generator = RainGenerator(RenderHarness.SAMPLE_RATE, SEED, preset)
            val seconds = 30.0
            RenderHarness.renderGenerator(generator, seconds)
            val measured = generator.dropCount / seconds
            val expected = preset.dropsPerSecond.toDouble()
            println("rain %.0f/s preset -> %.1f/s measured".format(expected, measured))
            assertTrue(
                abs(measured - expected) / expected <= 0.2,
                "drop rate $measured/s is more than 20 % from the preset's $expected/s",
            )
        }
    }

    @Test
    @DisplayName("a rain drop is a bounded, decaying burst (the voice pool releases)")
    fun rainVoicesAreReleased() {
        val generator = RainGenerator(RenderHarness.SAMPLE_RATE, SEED, RainPreset.DOWNPOUR)
        RenderHarness.renderGenerator(generator, 10.0)
        // 200 drops/s over 10 s is 2000 spawns through a 24-voice pool: if
        // voices never released, the pool would have stolen its way through
        // and the sound would be a continuous roar rather than a shower.
        assertTrue(generator.dropCount > 1_500, "only ${generator.dropCount} drops in 10 s")
    }

    @Test
    @DisplayName("ocean swells arrive every 9-15 s with more than 12 dB of depth")
    fun oceanSwellPeriodAndDepth() {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(SoundId.OCEAN),
            seconds = 150.0,
            warmUpSeconds = 2.0,
        )
        val windowSamples = capture.sampleRate / 4 // 250 ms
        val envelope = SignalAnalysis.rmsEnvelope(capture.left, windowSamples)
        // Smooth the envelope over ~2 s so the noise floor's own jitter cannot
        // register as a swell peak.
        val smoothed = movingAverage(envelope, 8)

        val maximum = smoothed.max()
        val minimum = smoothed.min()
        val depthDb = 20.0 * kotlin.math.log10(maximum / minimum)
        println("ocean depth %.1f dB (max %.4f min %.4f)".format(depthDb, maximum, minimum))
        assertTrue(depthDb > 12.0, "swell depth is only ${"%.1f".format(depthDb)} dB")

        val framesPerSecond = capture.sampleRate.toDouble() / windowSamples
        val spacing = SignalAnalysis.meanPeakSpacing(
            smoothed,
            minSeparation = (6 * framesPerSecond).toInt(),
            threshold = minimum + (maximum - minimum) * 0.5,
        )
        val periodSeconds = spacing / framesPerSecond
        println("ocean mean swell period %.1f s".format(periodSeconds))
        assertTrue(
            periodSeconds in 8.0..16.0,
            "mean swell period ${"%.1f".format(periodSeconds)} s is outside the 9-15 s design range",
        )
    }

    @Test
    @DisplayName("crickets put their dominant spectral peak in the stridulation band")
    fun cricketsPeakInBand() {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(SoundId.CRICKETS),
            seconds = 30.0,
            warmUpSeconds = 2.0,
        )
        val psd = SignalAnalysis.welchPsd(capture.left, 8192)
        val peakHz = SignalAnalysis.dominantFrequency(psd, capture.sampleRate, minHz = 100.0)
        println("crickets dominant peak %.0f Hz".format(peakHz))
        assertTrue(peakHz in 3_500.0..5_300.0, "dominant peak at $peakHz Hz, expected 3.5-5.3 kHz")
    }

    @Test
    @DisplayName("crickets actually pause between phrases")
    fun cricketsAreIntermittent() {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(SoundId.CRICKETS),
            seconds = 30.0,
            warmUpSeconds = 2.0,
        )
        val envelope = SignalAnalysis.rmsEnvelope(capture.left, capture.sampleRate / 20)
        val loud = envelope.count { it > envelope.max() * 0.5 }
        val fraction = loud.toDouble() / envelope.size
        println("crickets loud fraction %.2f".format(fraction))
        // A continuous drone would be near 1.0; silence would be 0.
        assertTrue(fraction in 0.02..0.8, "chirp duty cycle $fraction does not look like phrases")
    }

    @Test
    @DisplayName("thunder rolls are rare and never more than 6 dB above the rain bed")
    fun thunderIsRareAndSoft() {
        val generator = ThunderstormGenerator(RenderHarness.SAMPLE_RATE, SEED)
        val capture = RenderHarness.renderGenerator(generator, seconds = 180.0)
        val seconds = 180.0
        val rollsPerMinute = generator.rollCount / (seconds / 60.0)
        println("thunder rolls: ${generator.rollCount} in ${seconds}s (%.2f/min)".format(rollsPerMinute))
        // 25-90 s intervals means 0.7-2.4 per minute.
        assertTrue(rollsPerMinute in 0.5..2.6, "$rollsPerMinute rolls/minute is outside the design range")

        val envelope = SignalAnalysis.rmsEnvelope(capture.left, capture.sampleRate / 4)
        val sorted = envelope.sorted()
        val median = sorted[sorted.size / 2]
        val loudest = sorted.last()
        val headroomDb = 20.0 * kotlin.math.log10(loudest / median)
        println("thunder peak is %.1f dB over the median bed".format(headroomDb))
        // The spec caps a roll at +6 dB over the bed; 6.5 leaves room for the
        // measurement window without letting a real regression through.
        assertTrue(headroomDb <= 6.5, "a roll reached ${"%.1f".format(headroomDb)} dB over the bed")
        assertTrue(headroomDb >= 2.0, "rolls are inaudible: only ${"%.1f".format(headroomDb)} dB over the bed")
    }

    @Test
    @DisplayName("campfire crackles fire at the preset rate")
    fun campfireCrackleRate() {
        val generator = CampfireGenerator(RenderHarness.SAMPLE_RATE, SEED)
        val seconds = 30.0
        RenderHarness.renderGenerator(generator, seconds)
        val rate = generator.crackleCount / seconds
        println("campfire %.1f crackles/s".format(rate))
        val preset = CampfirePreset.DEFAULT
        assertTrue(
            rate in (preset.cracklesPerSecondMin * 0.8)..(preset.cracklesPerSecondMax * 1.2),
            "crackle rate $rate/s is outside the ${preset.cracklesPerSecondMin}-${preset.cracklesPerSecondMax}/s design range",
        )
    }

    @Test
    @DisplayName("wind energy sits in the resonant howl band")
    fun windIsBandLimited() {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(SoundId.WIND),
            seconds = 30.0,
            warmUpSeconds = 2.0,
        )
        val psd = SignalAnalysis.welchPsd(capture.left, 8192)
        val peakHz = SignalAnalysis.dominantFrequency(psd, capture.sampleRate, minHz = 60.0)
        println("wind dominant peak %.0f Hz".format(peakHz))
        assertTrue(peakHz in 150.0..900.0, "wind peaks at $peakHz Hz, expected the 250-700 Hz howl band")
    }

    @Test
    @DisplayName("stream energy sits in the resonator bank's range")
    fun streamIsInBand() {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(SoundId.STREAM),
            seconds = 20.0,
            warmUpSeconds = 2.0,
        )
        val psd = SignalAnalysis.welchPsd(capture.left, 8192)
        val peakHz = SignalAnalysis.dominantFrequency(psd, capture.sampleRate, minHz = 100.0)
        println("stream dominant peak %.0f Hz".format(peakHz))
        assertTrue(peakHz in 250.0..6_000.0, "stream peaks at $peakHz Hz, outside the 400 Hz-5 kHz bank")
    }

    @Test
    @DisplayName("stream bubbles at the preset's Poisson rate")
    fun streamBubbleRate() {
        val generator = StreamGenerator(RenderHarness.SAMPLE_RATE, SEED)
        val seconds = 20.0
        RenderHarness.renderGenerator(generator, seconds)
        val rate = generator.bubbleCount / seconds
        val preset = StreamPreset.DEFAULT
        val low = preset.burstsPerSecondMin * preset.resonators * 0.8
        val high = preset.burstsPerSecondMax * preset.resonators * 1.2
        println("stream %.0f bubbles/s across %d size classes".format(rate, preset.resonators))
        assertTrue(rate in low..high, "bubble rate $rate/s outside $low..$high")
    }

    @Test
    @DisplayName("stream is impulsive bubbling, not a filtered drone")
    fun streamIsImpulsive() {
        // The control is the same synth with its Poisson clocks driven so fast
        // that the excitation becomes continuous noise — i.e. exactly the
        // "six steady band-passes" design this generator replaced. Comparing
        // against it isolates the one thing that changed and needs no magic
        // absolute threshold.
        val bubbling = envelopeCrestDb(StreamGenerator(RenderHarness.SAMPLE_RATE, SEED))
        val drone = envelopeCrestDb(
            StreamGenerator(
                RenderHarness.SAMPLE_RATE,
                SEED,
                StreamPreset.DEFAULT.copy(
                    burstsPerSecondMin = 3_000f,
                    burstsPerSecondMax = 3_000f,
                ),
            ),
        )
        println("stream 5 ms envelope p95/p50: bubbling %.2f dB, continuous %.2f dB".format(bubbling, drone))
        assertTrue(
            bubbling > drone + 1.5,
            "the impulsive excitation is barely more dynamic than continuous noise " +
                "($bubbling dB vs $drone dB) - the brook will sound like a drone",
        )
    }

    private fun envelopeCrestDb(generator: StreamGenerator): Double {
        val capture = RenderHarness.renderGenerator(generator, seconds = 15.0, warmUpSeconds = 2.0)
        val envelope = SignalAnalysis.rmsEnvelope(capture.left, capture.sampleRate / 200)
        val sorted = envelope.sorted()
        val median = sorted[sorted.size / 2]
        val high = sorted[(sorted.size * 95) / 100]
        return 20.0 * kotlin.math.log10(high / median)
    }

    @Test
    @DisplayName("downpour is denser, fuller and wider-band than light rain")
    fun downpourIsHeavierThanRain() {
        assertTrue(
            RainPreset.DOWNPOUR.dropsPerSecond > RainPreset.LIGHT.dropsPerSecond * 2,
            "downpour should be at least twice as dense",
        )

        val rain = renderPsd(SoundId.RAIN)
        val downpour = renderPsd(SoundId.HEAVY_RAIN)
        // Both are normalised to the same RMS, so "heavier" has to show up as
        // a different *distribution*: more energy at the bottom (rain on
        // ground and roofs) and a band that reaches further up (sheets rather
        // than individual drops on leaves).
        val rainBody = bandRatioDb(rain, 150.0, 600.0, 1_500.0, 6_000.0)
        val downpourBody = bandRatioDb(downpour, 150.0, 600.0, 1_500.0, 6_000.0)
        println("low/mid balance: rain %.1f dB, downpour %.1f dB".format(rainBody, downpourBody))
        assertTrue(
            downpourBody > rainBody + 1.0,
            "downpour must have a fuller low end than light rain",
        )
        assertTrue(
            RainPreset.DOWNPOUR.bedHighHz > RainPreset.LIGHT.bedHighHz,
            "downpour's sheet must reach higher than light rain's",
        )
    }

    private fun renderPsd(id: SoundId): DoubleArray {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(id),
            seconds = 20.0,
            warmUpSeconds = 2.0,
        )
        return SignalAnalysis.welchPsd(capture.left, 8192)
    }

    private fun bandRatioDb(
        psd: DoubleArray,
        highLow: Double,
        highHigh: Double,
        lowLow: Double,
        lowHigh: Double,
    ): Double {
        val fftSize = (psd.size - 1) * 2
        val binHz = RenderHarness.SAMPLE_RATE.toDouble() / fftSize
        var high = 0.0
        var highCount = 0
        var low = 0.0
        var lowCount = 0
        for (k in psd.indices) {
            val f = k * binHz
            if (f in highLow..highHigh) {
                high += psd[k]
                highCount++
            }
            if (f in lowLow..lowHigh) {
                low += psd[k]
                lowCount++
            }
        }
        return 10.0 * kotlin.math.log10((high / highCount) / (low / lowCount))
    }

    private fun movingAverage(x: DoubleArray, radius: Int): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            var sum = 0.0
            var count = 0
            for (j in (i - radius)..(i + radius)) {
                if (j in x.indices) {
                    sum += x[j]
                    count++
                }
            }
            out[i] = sum / count
        }
        return out
    }

    private companion object {
        const val SEED = 0x0B0B_0B0BL
    }
}
