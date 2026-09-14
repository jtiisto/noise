package dev.jtiisto.noise.core.audio

import dev.jtiisto.noise.core.audio.generators.AirplaneGenerator
import dev.jtiisto.noise.core.audio.generators.BlueNoiseGenerator
import dev.jtiisto.noise.core.audio.generators.BrownNoiseGenerator
import dev.jtiisto.noise.core.audio.generators.CampfireGenerator
import dev.jtiisto.noise.core.audio.generators.CampfirePreset
import dev.jtiisto.noise.core.audio.generators.CricketsGenerator
import dev.jtiisto.noise.core.audio.generators.CricketsPreset
import dev.jtiisto.noise.core.audio.generators.FanGenerator
import dev.jtiisto.noise.core.audio.generators.GreyNoiseGenerator
import dev.jtiisto.noise.core.audio.generators.OceanGenerator
import dev.jtiisto.noise.core.audio.generators.OceanPreset
import dev.jtiisto.noise.core.audio.generators.PinkNoiseGenerator
import dev.jtiisto.noise.core.audio.generators.RainGenerator
import dev.jtiisto.noise.core.audio.generators.RainPreset
import dev.jtiisto.noise.core.audio.generators.StreamGenerator
import dev.jtiisto.noise.core.audio.generators.StreamPreset
import dev.jtiisto.noise.core.audio.generators.ThunderstormGenerator
import dev.jtiisto.noise.core.audio.generators.VioletNoiseGenerator
import dev.jtiisto.noise.core.audio.generators.WhiteNoiseGenerator
import dev.jtiisto.noise.core.audio.generators.WindGenerator
import dev.jtiisto.noise.core.audio.testing.RenderHarness
import dev.jtiisto.noise.core.audio.testing.SignalAnalysis
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class GeneratorsTest {

    @Test
    @DisplayName("the factory maps every catalog entry to its generator")
    fun factoryCoversTheCatalog() {
        val expected = mapOf(
            SoundId.WHITE to WhiteNoiseGenerator::class,
            SoundId.PINK to PinkNoiseGenerator::class,
            SoundId.BROWN to BrownNoiseGenerator::class,
            SoundId.BLUE to BlueNoiseGenerator::class,
            SoundId.VIOLET to VioletNoiseGenerator::class,
            SoundId.GREY to GreyNoiseGenerator::class,
            SoundId.RAIN to RainGenerator::class,
            SoundId.HEAVY_RAIN to RainGenerator::class,
            SoundId.THUNDERSTORM to ThunderstormGenerator::class,
            SoundId.OCEAN to OceanGenerator::class,
            SoundId.WIND to WindGenerator::class,
            SoundId.CAMPFIRE to CampfireGenerator::class,
            SoundId.STREAM to StreamGenerator::class,
            SoundId.CRICKETS to CricketsGenerator::class,
            SoundId.FAN to FanGenerator::class,
            SoundId.AIRPLANE to AirplaneGenerator::class,
        )
        assertEquals(SoundId.entries.size, expected.size, "the catalog grew without a generator")
        for ((id, type) in expected) {
            assertEquals(type, Generators.create(id, 48_000, 1L)::class, "wrong generator for $id")
        }
    }

    @ParameterizedTest
    @EnumSource(SoundId::class)
    @DisplayName("every generator works at 44.1 kHz as well as 48 kHz")
    fun generatorsWorkAtOtherSampleRates(id: SoundId) {
        // Devices whose native output is 44.1 kHz get a renderer built at that
        // rate; a generator that hard-codes 48 kHz would be detuned there.
        val generator = Generators.create(id, 44_100, 7L)
        val capture = RenderHarness.renderGenerator(
            generator,
            seconds = 4.0,
            warmUpSeconds = 2.0,
            sampleRate = 44_100,
        )
        assertTrue(SignalAnalysis.allFinite(capture.left), "$id produced non-finite output")
        val db = SignalAnalysis.rmsDb(capture.left)
        val target = if (id == SoundId.CRICKETS) -26.0 else -20.0
        // A short window at a different rate: only a coarse check that the
        // level did not move by an octave-sized amount.
        assertTrue(db in (target - 6)..(target + 6), "$id at 44.1 kHz measured $db dBFS")
    }

    @Test
    @DisplayName("presets are honoured, not ignored")
    fun presetsChangeTheSound() {
        // Halving outputGain should drop the level by ~6 dB — but only in the
        // linear region. The campfire's soft limiter engages on peaks at the
        // default gain, so comparing DEFAULT against DEFAULT/2 would measure the
        // limiter's (intended) compression, not the preset. Both gains here sit
        // well below the limiter knee, so this cleanly checks that outputGain is
        // honoured rather than ignored.
        val quietFire = CampfireGenerator(
            48_000,
            5L,
            CampfirePreset.DEFAULT.copy(outputGain = CampfirePreset.DEFAULT.outputGain / 4f),
        )
        val loudFire = CampfireGenerator(
            48_000,
            5L,
            CampfirePreset.DEFAULT.copy(outputGain = CampfirePreset.DEFAULT.outputGain / 2f),
        )
        val quiet = SignalAnalysis.rmsDb(RenderHarness.renderGenerator(quietFire, 5.0, 2.0).left)
        val loud = SignalAnalysis.rmsDb(RenderHarness.renderGenerator(loudFire, 5.0, 2.0).left)
        assertEquals(-6.0, quiet - loud, 0.5)

        val denseRain = RainGenerator(48_000, 5L, RainPreset.LIGHT.copy(dropsPerSecond = 400f))
        RenderHarness.renderGenerator(denseRain, 5.0)
        assertTrue(denseRain.dropCount > 1_600, "the dropsPerSecond preset was ignored")

        val fastOcean = OceanGenerator(
            48_000,
            5L,
            OceanPreset.DEFAULT.copy(minPeriodSeconds = 2f, maxPeriodSeconds = 3f),
        )
        val capture = RenderHarness.renderGenerator(fastOcean, 30.0, 2.0)
        val envelope = SignalAnalysis.rmsEnvelope(capture.left, 4_800)
        val spacing = SignalAnalysis.meanPeakSpacing(envelope, 10, envelope.average())
        assertTrue(spacing in 15.0..40.0, "a 2-3 s wave period gave $spacing frames of 0.1 s")

        val oneResonator = StreamGenerator(48_000, 5L, StreamPreset.DEFAULT.copy(resonators = 1))
        assertTrue(
            SignalAnalysis.allFinite(RenderHarness.renderGenerator(oneResonator, 2.0).left),
            "a single-resonator stream broke the pan spread",
        )

        val oneCricket = CricketsGenerator(48_000, 5L, CricketsPreset.DEFAULT.copy(individuals = 1))
        assertTrue(SignalAnalysis.allFinite(RenderHarness.renderGenerator(oneCricket, 2.0).left))
    }

    @Test
    @DisplayName("thunderstorm reuses the rain generator with a trimmed bed")
    fun thunderstormTrimsItsBed() {
        val bare = RainGenerator(48_000, 3L, RainPreset.DOWNPOUR)
        val trimmed = RainGenerator(48_000, 3L, RainPreset.DOWNPOUR, levelTrim = 0.5f)
        val bareDb = SignalAnalysis.rmsDb(RenderHarness.renderGenerator(bare, 6.0, 2.0).left)
        val trimmedDb = SignalAnalysis.rmsDb(RenderHarness.renderGenerator(trimmed, 6.0, 2.0).left)
        assertEquals(-6.0, trimmedDb - bareDb, 0.2)
    }
}
