package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.testing.RenderHarness
import dev.jtiisto.noise.core.audio.testing.SignalAnalysis
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

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
    @DisplayName("thunder rolls are clearly audible over the rain but never clip")
    fun thunderRollsAreAudibleButBounded() {
        val generator = ThunderstormGenerator(RenderHarness.SAMPLE_RATE, SEED)
        val capture = RenderHarness.renderGenerator(generator, seconds = 180.0)
        val seconds = 180.0
        val rollsPerMinute = generator.rollCount / (seconds / 60.0)
        println("thunder rolls: ${generator.rollCount} in ${seconds}s (%.2f/min)".format(rollsPerMinute))
        // 15-45 s gaps plus a 5-11 s roll means roughly 1.1-3.2 per minute.
        assertTrue(rollsPerMinute in 1.0..3.6, "$rollsPerMinute rolls/minute is outside the design range")

        val envelope = SignalAnalysis.rmsEnvelope(capture.left, capture.sampleRate / 4)
        val sorted = envelope.sorted()
        val median = sorted[sorted.size / 2]
        val loudest = sorted.last()
        val headroomDb = 20.0 * kotlin.math.log10(loudest / median)
        println("thunder rolls %.1f dB over the median bed".format(headroomDb))
        // The rolls are a deliberate, prominent event — the listener must
        // actually hear thunder rolling, not a subtle swell — so they must
        // clearly rise above the rain bed. (This replaces the earlier "never
        // more than 6 dB" cap, which made thunder near-inaudible.)
        assertTrue(headroomDb >= 8.0, "thunder is too quiet: only ${"%.1f".format(headroomDb)} dB over the bed")
        // But the generator's own soft limiter keeps the absolute peak under
        // the 0.9 mix-headroom ceiling, so a roll never clips the mix. The
        // exact per-channel peak is pinned by GeneratorCalibrationTest.
        val peak = maxOf(SignalAnalysis.peak(capture.left), SignalAnalysis.peak(capture.right))
        assertTrue(peak <= 0.9f, "a roll peaked at ${"%.3f".format(peak)}, past the 0.9 ceiling")
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
    @DisplayName("campfire cracks are impulsive and broadband, not soft pitched blips")
    fun campfireCracksAreSharpAndBroadband() {
        // This asserts the crackle *design* — a real fire's cracks are near-
        // instant broadband clicks (measured attack ~0.1 ms, spectral flatness
        // ~0.13-0.28, centroid ~4-5 kHz, wide centroid spread) — where the old
        // resonant-band crackle was soft (attack ~3 ms) and pitched (flatness
        // ~0.02, narrow). Metrics after Farnell/`compare_fire.py`, computed here
        // with the shared FFT utilities.
        //
        // We measure the crack *shape* with the -20 dBFS peak limiter effectively
        // disabled (a low output gain, well under its knee), so this isolates the
        // synthesis from the peak-limiting the loudness calibration must apply to
        // so peaky a sound — the 0.9 peak ceiling is `GeneratorCalibrationTest`'s
        // job, not this one.
        val preset = CampfirePreset.DEFAULT.copy(outputGain = 0.15f)
        val gen = CampfireGenerator(RenderHarness.SAMPLE_RATE, SEED, preset)
        val capture = RenderHarness.renderGenerator(gen, seconds = 20.0, warmUpSeconds = 3.0)
        val sr = capture.sampleRate
        val mono = FloatArray(capture.frames) { (capture.left[it] + capture.right[it]) * 0.5f }

        val onsets = detectCrackOnsets(mono, sr)
        assertTrue(onsets.size >= 30, "only ${onsets.size} crack events detected")

        val attacks = ArrayList<Double>()
        val flatness = ArrayList<Double>()
        val centroids = ArrayList<Double>()
        val pre = (sr * 0.002).toInt()
        val post = (sr * 0.030).toInt()
        for (c in onsets) {
            val lo = (c - pre).coerceAtLeast(0)
            val hi = (c + post).coerceAtMost(mono.size)
            if (hi - lo < 512) continue
            val seg = mono.copyOfRange(lo, hi)
            attacks += attackRiseMs(seg, sr)
            val psd = SignalAnalysis.welchPsd(seg, 256)
            flatness += spectralFlatness(psd)
            centroids += spectralCentroid(psd, sr)
        }

        val atkP10 = quantile(attacks, 0.10)
        val atkMed = quantile(attacks, 0.50)
        val flatMed = quantile(flatness, 0.50)
        val centMed = quantile(centroids, 0.50)
        val centP10 = quantile(centroids, 0.10)
        val centP90 = quantile(centroids, 0.90)
        println(
            "campfire cracks: attack p10 %.2f ms med %.2f ms | flatness med %.2f | centroid med %.0f Hz spread %.0f-%.0f".format(
                atkP10, atkMed, flatMed, centMed, centP10, centP90,
            ),
        )

        // Impulsive: the sharp cracks rise in a fraction of a millisecond (the
        // old design's sharpest was ~2.5 ms), and the typical crack well under 1 ms.
        assertTrue(atkP10 < 0.5, "sharpest cracks rise in ${"%.2f".format(atkP10)} ms, not impulsive (< 0.5 ms)")
        assertTrue(atkMed < 1.0, "median crack attack ${"%.2f".format(atkMed)} ms is too soft (>= 1 ms)")
        // Broadband, not a pitched resonant ring: flatness far above the old ~0.03.
        assertTrue(flatMed > 0.12, "median flatness ${"%.2f".format(flatMed)} is not broadband (old resonant crackle was ~0.03)")
        // Bright, and varied from low woody pops to bright snaps (wide spread).
        assertTrue(centMed > 3_000, "median centroid ${"%.0f".format(centMed)} Hz is too dull (< 3 kHz)")
        assertTrue(
            centP10 < 2_500 && centP90 > 5_500,
            "centroid spread ${"%.0f".format(centP10)}-${"%.0f".format(centP90)} Hz is too narrow/uniform",
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

    // ---- Polish-round behaviours -------------------------------------------
    // Each of the four tests below pairs the real generator against the same
    // generator with one preset knob turned off. That control is the design
    // the feature replaced, so the assertion cannot pass by accident and it
    // needs no magic absolute threshold.

    @Test
    @DisplayName("rain's close-drop stream fires at its own, much sparser rate")
    fun rainCloseDropRate() {
        for (preset in listOf(RainPreset.LIGHT, RainPreset.DOWNPOUR)) {
            val generator = RainGenerator(RenderHarness.SAMPLE_RATE, SEED, preset)
            val seconds = 60.0
            RenderHarness.renderGenerator(generator, seconds)
            val measured = generator.closeDropCount / seconds
            val expected = preset.closeDropsPerSecond.toDouble()
            println("close drops: %.1f/s preset -> %.2f/s measured".format(expected, measured))
            assertTrue(
                abs(measured - expected) / expected <= 0.25,
                "close-drop rate $measured/s is more than 25 % from the preset's $expected/s",
            )
        }
    }

    @Test
    @DisplayName("close drops put transients above anything the sheet alone reaches")
    fun rainCloseDropsAreAudibleTransients() {
        val preset = RainPreset.LIGHT
        val withClose = RenderHarness.renderGenerator(
            RainGenerator(RenderHarness.SAMPLE_RATE, SEED, preset),
            seconds = 20.0,
            warmUpSeconds = 2.0,
        )
        val sheetOnly = RenderHarness.renderGenerator(
            RainGenerator(RenderHarness.SAMPLE_RATE, SEED, preset.copy(closeDropsPerSecond = 0f)),
            seconds = 20.0,
            warmUpSeconds = 2.0,
        )
        // A single peak sample is far too noisy a statistic for the sheet,
        // whose amplitude distribution has a long tail. The 99.99th percentile
        // is stable, and "how much of the signal sits above the sheet's own
        // 99.99th percentile" is exactly the question - a foreground layer
        // multiplies it, and nothing else in the generator can.
        val sheetLoud = percentile(sheetOnly.left, 0.9999)
        val closeLoud = percentile(withClose.left, 0.9999)
        val sheetHits = countAbove(sheetOnly.left, sheetLoud)
        val closeHits = countAbove(withClose.left, sheetLoud)
        println(
            "rain p99.99: sheet %.3f, with close drops %.3f; samples above the sheet's p99.99: %d -> %d"
                .format(sheetLoud, closeLoud, sheetHits, closeHits),
        )
        // Removing the close layer makes both of these exactly 1.0, so the
        // margins below are margins over "feature absent", not over noise.
        assertTrue(
            closeLoud > sheetLoud * 1.12f,
            "close drops only moved the 99.99th percentile from $sheetLoud to $closeLoud",
        )
        assertTrue(
            closeHits > sheetHits * 3,
            "close drops only produced $closeHits loud samples against the sheet's $sheetHits",
        )
        assertTrue(
            SignalAnalysis.peak(withClose.left) > SignalAnalysis.peak(sheetOnly.left),
            "close drops did not raise the peak above the sheet's",
        )
    }

    @Test
    @DisplayName("a thunder roll darkens as it decays")
    fun thunderRollDarkens() {
        // The bed is trimmed to silence and the crack disabled so the only
        // thing measured is the roll itself, and the roll is pinned to one
        // length and one starting cutoff so the slices are deterministic.
        val preset = ThunderPreset.DEFAULT.copy(
            firstMinSeconds = 0.5f,
            firstMaxSeconds = 0.5f,
            minDurationSeconds = ROLL_SECONDS,
            maxDurationSeconds = ROLL_SECONDS,
            minCutoffHz = 200f,
            maxCutoffHz = 200f,
            crackProbability = 0f,
            bedTrim = 0f,
        )
        val sweeping = rollTiltDb(ThunderPreset = preset)
        val flat = rollTiltDb(ThunderPreset = preset.copy(endCutoffFraction = 1f))
        println(
            "thunder tilt (last third - first third): sweeping %.2f dB, no sweep %.2f dB"
                .format(sweeping, flat),
        )
        // With the sweep disabled the two windows measure the same filter, so
        // the control lands near 0 dB; anything clearly negative can only come
        // from the cutoff having moved.
        assertTrue(
            sweeping < -6.0,
            "the roll's high/low balance only moved $sweeping dB; it is not darkening",
        )
        assertTrue(
            abs(flat) < 2.5,
            "with the sweep disabled the balance still moved $flat dB - something else is tilting it",
        )
    }

    @Test
    @DisplayName("thunderstorm reset reproduces the rumble after a roll has drawn brown noise")
    fun thunderstormResetReproducesAfterRoll() {
        // Force a roll almost immediately so the brown-noise rumble is actually
        // drawn before the reset. The generic determinism check renders only 1 s,
        // before the 5-15 s first-roll window, so it never touches the brown
        // source and would miss a reseed regression here (found by Codex).
        val preset = ThunderPreset.DEFAULT.copy(firstMinSeconds = 0.2f, firstMaxSeconds = 0.2f)
        val generator = ThunderstormGenerator(RenderHarness.SAMPLE_RATE, SEED, preset)
        generator.reset(4242L)
        val first = RenderHarness.renderGenerator(generator, seconds = 4.0)
        generator.reset(4242L)
        val second = RenderHarness.renderGenerator(generator, seconds = 4.0)
        assertTrue(
            first.left.contentEquals(second.left) && first.right.contentEquals(second.right),
            "thunderstorm reset(seed) is not reproducible once a roll has drawn brown noise",
        )
    }

    @Test
    @DisplayName("wind's buffet adds gust-linked energy below 80 Hz")
    fun windBuffetIsGustLinked() {
        val seconds = 60.0
        val withBuffet = RenderHarness.renderGenerator(
            WindGenerator(RenderHarness.SAMPLE_RATE, SEED),
            seconds = seconds,
            warmUpSeconds = 2.0,
        )
        val without = RenderHarness.renderGenerator(
            WindGenerator(RenderHarness.SAMPLE_RATE, SEED, WindPreset.DEFAULT.copy(buffetLevel = 0f)),
            seconds = seconds,
            warmUpSeconds = 2.0,
        )
        val band = SignalAnalysis.bandLimit(withBuffet.left, withBuffet.sampleRate, 30f, 80f)
        val bandOff = SignalAnalysis.bandLimit(without.left, without.sampleRate, 30f, 80f)
        val gainDb = SignalAnalysis.rmsDb(band) - SignalAnalysis.rmsDb(bandOff)

        // Half-second windows: a 30-80 Hz band holds few cycles per window, so
        // a short one would measure estimator variance rather than gusting.
        val envelope = SignalAnalysis.rmsEnvelope(band, withBuffet.sampleRate / 2)
        val sorted = envelope.sorted()
        val loud = sorted[(sorted.size * 90) / 100]
        val quiet = sorted[sorted.size / 10]
        val swingDb = 20.0 * kotlin.math.log10(loud / quiet)
        println("wind 30-80 Hz: buffet adds %.1f dB, gust swing %.1f dB".format(gainDb, swingDb))

        // Measured with the buffet on: +17.7 dB and a 17.9 dB swing. With
        // buffetLevel = 0 the band is only the howl's filter skirt, which
        // gives 0 dB of gain and still swings 11.5 dB (the howl is itself
        // gust-modulated) - so the swing threshold has to sit above 11.5 to
        // be a test of the buffet rather than of the howl.
        assertTrue(gainDb >= 8.0, "the buffet only added $gainDb dB below 80 Hz")
        assertTrue(swingDb >= 14.0, "the 30-80 Hz band only swings $swingDb dB; it is not gust-linked")
    }

    @Test
    @DisplayName("the ocean's second-order body filter keeps crests out of the top octaves")
    fun oceanBodyRollsOffTheTop() {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(SoundId.OCEAN),
            seconds = 40.0,
            warmUpSeconds = 2.0,
        )
        val sr = capture.sampleRate
        val low = SignalAnalysis.bandRmsDb(capture.left, sr, 177f, 354f)
        val eightK = SignalAnalysis.bandRmsDb(capture.left, sr, 5_657f, 11_314f)
        val sixteenK = SignalAnalysis.bandRmsDb(capture.left, sr, 11_314f, 20_000f)
        println(
            "ocean octave bands: 250 Hz %.1f, 8 kHz %.1f, 16 kHz %.1f dBFS".format(
                low, eightK, sixteenK,
            ),
        )
        // Measured by actually swapping the body filter back to a one pole:
        // -11.2 dB and -15.7 dB relative to 250 Hz, with 4.5 dB of roll-off
        // between the two bands. Second order measures -15.2, -28.8 and
        // 13.6 dB. Every threshold below sits between the two, so a
        // regression to one pole fails all three of them.
        val slopePerOctave = eightK - sixteenK
        println("ocean 8->16 kHz slope: %.1f dB/oct".format(slopePerOctave))
        assertTrue(eightK - low <= -13.0, "8 kHz is only ${eightK - low} dB below 250 Hz")
        assertTrue(sixteenK - low <= -25.0, "16 kHz is only ${sixteenK - low} dB below 250 Hz")
        assertTrue(
            slopePerOctave >= 10.0,
            "the body only rolls off $slopePerOctave dB between 8 and 16 kHz; that is one-pole behaviour",
        )
    }

    /**
     * High-band minus low-band energy in the last third of a roll relative to
     * the first third. Negative means the roll got darker.
     */
    private fun rollTiltDb(ThunderPreset: ThunderPreset): Double {
        val generator = ThunderstormGenerator(RenderHarness.SAMPLE_RATE, SEED, ThunderPreset)
        val capture = RenderHarness.renderGenerator(generator, seconds = ROLL_SECONDS * 1.6 + 1.0)
        val sr = capture.sampleRate
        val start = (0.5 * sr).toInt()
        val third = (ROLL_SECONDS / 3.0 * sr).toInt()
        // First third of the event against the tail that follows it, where the
        // cutoff has finished its sweep and is held down. Comparing the first
        // and last thirds of the nominal window understates the effect,
        // because the sweep is still in progress for most of the last third.
        val first = capture.left.copyOfRange(start, start + third)
        val tailStart = start + (ROLL_SECONDS * sr).toInt()
        val last = capture.left.copyOfRange(tailStart, tailStart + third)
        val firstTilt = tiltDb(first, sr)
        val lastTilt = tiltDb(last, sr)
        println("  roll tilt: first third %.1f dB, last third %.1f dB".format(firstTilt, lastTilt))
        return lastTilt - firstTilt
    }

    /**
     * Energy at 150-350 Hz relative to 30-90 Hz, in dB, measured from a Welch
     * periodogram rather than with band-pass filters: inside a roll the low
     * band is more than 30 dB louder than the high one, and a 24 dB/oct
     * filter skirt leaks enough to swamp the reading. FFT bins do not.
     */
    private fun tiltDb(slice: FloatArray, @Suppress("UNUSED_PARAMETER") sampleRate: Int): Double =
        bandRatioDb(SignalAnalysis.welchPsd(slice, 8192), 150.0, 350.0, 30.0, 90.0)

    /** [fraction]-th quantile of |x|, e.g. 0.9999 for the 99.99th percentile. */
    private fun percentile(x: FloatArray, fraction: Double): Float {
        val magnitudes = FloatArray(x.size) { abs(x[it]) }
        magnitudes.sort()
        return magnitudes[((magnitudes.size - 1) * fraction).toInt()]
    }

    private fun countAbove(x: FloatArray, threshold: Float): Int {
        var n = 0
        for (v in x) if (abs(v) > threshold) n++
        return n
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

    // ---- Campfire crack-transient analysis (mirrors research/compare_fire.py) --

    /**
     * Onset sample indices of the loudest transients: the top 1 % of a 3 ms
     * moving-average of |x|, thinned to one per 30 ms and snapped to the local
     * peak. This is the same detector the offline `compare_fire.py` uses.
     */
    private fun detectCrackOnsets(x: FloatArray, sampleRate: Int): List<Int> {
        val win = (sampleRate * 0.003).toInt().coerceAtLeast(1)
        val smooth = movingAverageAbs(x, win)
        val threshold = quantileFloat(smooth, 0.99)
        val minSpacing = (sampleRate * 0.030).toInt()
        val lookBack = (sampleRate * 0.002).toInt()
        val lookAhead = (sampleRate * 0.030).toInt()
        val onsets = ArrayList<Int>()
        var last = Int.MIN_VALUE / 2
        var i = 0
        while (i < smooth.size) {
            if (smooth[i] > threshold && i - last > minSpacing) {
                val lo = (i - lookBack).coerceAtLeast(0)
                val hi = (i + lookAhead).coerceAtMost(x.size)
                var peak = lo
                var best = -1f
                for (j in lo until hi) {
                    val a = abs(x[j])
                    if (a > best) { best = a; peak = j }
                }
                if (onsets.isEmpty() || peak - onsets.last() > minSpacing) {
                    onsets += peak
                    last = peak
                }
            }
            i++
        }
        return onsets
    }

    private fun movingAverageAbs(x: FloatArray, win: Int): FloatArray {
        val out = FloatArray(x.size)
        var sum = 0.0
        for (i in x.indices) {
            sum += abs(x[i])
            if (i >= win) sum -= abs(x[i - win])
            val n = if (i < win) i + 1 else win
            out[i] = (sum / n).toFloat()
        }
        return out
    }

    /** 10-90 % rise time of |seg| up to its peak, in milliseconds. */
    private fun attackRiseMs(seg: FloatArray, sampleRate: Int): Double {
        var peak = 0f
        var peakIndex = 0
        for (i in seg.indices) {
            val a = abs(seg[i])
            if (a > peak) { peak = a; peakIndex = i }
        }
        if (peak < 1e-4f) return Double.MAX_VALUE
        var i10 = -1
        var i90 = -1
        for (i in 0..peakIndex) {
            val a = abs(seg[i])
            if (i10 < 0 && a >= 0.1f * peak) i10 = i
            if (a >= 0.9f * peak) { i90 = i; break }
        }
        if (i10 < 0 || i90 < 0) return Double.MAX_VALUE
        return (i90 - i10).toDouble() / sampleRate * 1000.0
    }

    /** Wiener spectral flatness (geometric mean / arithmetic mean) over the band, DC excluded. */
    private fun spectralFlatness(psd: DoubleArray): Double {
        var logSum = 0.0
        var linSum = 0.0
        var n = 0
        for (k in 1 until psd.size) {
            val p = psd[k] + 1e-12
            logSum += ln(p)
            linSum += p
            n++
        }
        if (n == 0 || linSum <= 0.0) return 0.0
        return exp(logSum / n) / (linSum / n)
    }

    private fun spectralCentroid(psd: DoubleArray, sampleRate: Int): Double {
        val fftSize = (psd.size - 1) * 2
        val binHz = sampleRate.toDouble() / fftSize
        var num = 0.0
        var den = 0.0
        for (k in psd.indices) {
            num += k * binHz * psd[k]
            den += psd[k]
        }
        return if (den <= 0.0) 0.0 else num / den
    }

    private fun quantile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * q).toInt()]
    }

    private fun quantileFloat(values: FloatArray, q: Double): Float {
        val sorted = values.copyOf()
        sorted.sort()
        return sorted[((sorted.size - 1) * q).toInt()]
    }

    private companion object {
        const val SEED = 0x0B0B_0B0BL
        const val ROLL_SECONDS = 8f
    }
}
