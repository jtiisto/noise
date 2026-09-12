package dev.jtiisto.noise.ui.critter

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundCategory
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CritterKindTest {

    private fun mix(vararg ids: SoundId) = Mix(ids.map { dev.jtiisto.noise.core.model.SoundLayer(it) })

    @Test
    fun `each rain-family sound shows the frog`() {
        assertEquals(CritterKind.FROG, critterFor(mix(SoundId.RAIN)))
        assertEquals(CritterKind.FROG, critterFor(mix(SoundId.HEAVY_RAIN)))
        assertEquals(CritterKind.FROG, critterFor(mix(SoundId.THUNDERSTORM)))
    }

    @Test
    fun `each remaining nature sound shows its own animal`() {
        assertEquals(CritterKind.WHALE, critterFor(mix(SoundId.OCEAN)))
        assertEquals(CritterKind.BIRD, critterFor(mix(SoundId.WIND)))
        assertEquals(CritterKind.FOX, critterFor(mix(SoundId.CAMPFIRE)))
        assertEquals(CritterKind.DUCK, critterFor(mix(SoundId.STREAM)))
        assertEquals(CritterKind.FIREFLY, critterFor(mix(SoundId.CRICKETS)))
    }

    @Test
    fun `an empty mix shows the sleeping cat`() {
        assertEquals(CritterKind.CAT, critterFor(Mix.EMPTY))
    }

    @Test
    fun `a mix of only noise shows the cat`() {
        assertEquals(CritterKind.CAT, critterFor(mix(SoundId.WHITE, SoundId.BROWN)))
        assertEquals(CritterKind.CAT, critterFor(mix(SoundId.GREY)))
    }

    @Test
    fun `a mix of only ambience shows the cat`() {
        assertEquals(CritterKind.CAT, critterFor(mix(SoundId.FAN, SoundId.AIRPLANE)))
    }

    @Test
    fun `noise and ambience together, with no nature, still show the cat`() {
        assertEquals(CritterKind.CAT, critterFor(mix(SoundId.FAN, SoundId.BROWN)))
    }

    @Test
    fun `the lead is the first nature sound in mix order, not the first layer`() {
        // Noise leads, but the nature sound behind it picks the critter.
        assertEquals(CritterKind.WHALE, critterFor(mix(SoundId.BROWN, SoundId.OCEAN)))
        // Ambience first, nature second.
        assertEquals(CritterKind.DUCK, critterFor(mix(SoundId.FAN, SoundId.STREAM)))
    }

    @Test
    fun `with two nature sounds the earlier one wins`() {
        assertEquals(CritterKind.FROG, critterFor(mix(SoundId.RAIN, SoundId.OCEAN)))
        assertEquals(CritterKind.WHALE, critterFor(mix(SoundId.OCEAN, SoundId.RAIN)))
        assertEquals(CritterKind.BIRD, critterFor(mix(SoundId.WIND, SoundId.CAMPFIRE)))
    }

    @Test
    fun `every nature sound in the catalog maps to its own critter, never the default`() {
        // Guards the mapping: a nature sound added later without an entry would
        // silently fall back to the cat, and this fails loudly when it does.
        SoundId.inCategory(SoundCategory.NATURE).forEach { id ->
            assertNotEquals(CritterKind.CAT, critterFor(mix(id)), "$id has no critter of its own")
        }
    }
}
