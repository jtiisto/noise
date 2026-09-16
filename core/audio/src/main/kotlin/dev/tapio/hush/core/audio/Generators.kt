package dev.tapio.hush.core.audio

import dev.tapio.hush.core.audio.generators.AirplaneGenerator
import dev.tapio.hush.core.audio.generators.BlueNoiseGenerator
import dev.tapio.hush.core.audio.generators.BrownNoiseGenerator
import dev.tapio.hush.core.audio.generators.CampfireGenerator
import dev.tapio.hush.core.audio.generators.CricketsGenerator
import dev.tapio.hush.core.audio.generators.FanGenerator
import dev.tapio.hush.core.audio.generators.GreyNoiseGenerator
import dev.tapio.hush.core.audio.generators.OceanGenerator
import dev.tapio.hush.core.audio.generators.PinkNoiseGenerator
import dev.tapio.hush.core.audio.generators.RainGenerator
import dev.tapio.hush.core.audio.generators.RainPreset
import dev.tapio.hush.core.audio.generators.StreamGenerator
import dev.tapio.hush.core.audio.generators.ThunderstormGenerator
import dev.tapio.hush.core.audio.generators.VioletNoiseGenerator
import dev.tapio.hush.core.audio.generators.WhiteNoiseGenerator
import dev.tapio.hush.core.audio.generators.WindGenerator
import dev.tapio.hush.core.model.SoundId

/**
 * Builds the [SoundGenerator] for a catalog entry.
 *
 * An interface rather than a bare function so tests can hand [MixRenderer] a
 * deterministic stand-in (a DC or sine source) and assert on mixing behaviour
 * without noise in the way — while production code keeps using the single
 * [Generators] object.
 */
fun interface GeneratorFactory {
    fun create(id: SoundId, sampleRate: Int, seed: Long): SoundGenerator
}

/** The catalog's real generators. Every [SoundId] maps to exactly one. */
object Generators : GeneratorFactory {

    override fun create(id: SoundId, sampleRate: Int, seed: Long): SoundGenerator = when (id) {
        SoundId.WHITE -> WhiteNoiseGenerator(sampleRate, seed)
        SoundId.PINK -> PinkNoiseGenerator(sampleRate, seed)
        SoundId.BROWN -> BrownNoiseGenerator(sampleRate, seed)
        SoundId.BLUE -> BlueNoiseGenerator(sampleRate, seed)
        SoundId.VIOLET -> VioletNoiseGenerator(sampleRate, seed)
        SoundId.GREY -> GreyNoiseGenerator(sampleRate, seed)
        SoundId.RAIN -> RainGenerator(sampleRate, seed, RainPreset.LIGHT)
        // Downpour is the same synth with a heavier preset, exactly as the
        // spec intends: one code path to maintain, two textures.
        SoundId.HEAVY_RAIN -> RainGenerator(sampleRate, seed, RainPreset.DOWNPOUR)
        SoundId.THUNDERSTORM -> ThunderstormGenerator(sampleRate, seed)
        SoundId.OCEAN -> OceanGenerator(sampleRate, seed)
        SoundId.WIND -> WindGenerator(sampleRate, seed)
        SoundId.CAMPFIRE -> CampfireGenerator(sampleRate, seed)
        SoundId.STREAM -> StreamGenerator(sampleRate, seed)
        SoundId.CRICKETS -> CricketsGenerator(sampleRate, seed)
        SoundId.FAN -> FanGenerator(sampleRate, seed)
        SoundId.AIRPLANE -> AirplaneGenerator(sampleRate, seed)
    }
}
