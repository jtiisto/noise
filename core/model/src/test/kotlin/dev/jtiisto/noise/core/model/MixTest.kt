package dev.jtiisto.noise.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MixTest {
    @Test
    fun `with adds up to the limit then refuses`() {
        val one = Mix.EMPTY.with(SoundId.RAIN)!!
        val two = one.with(SoundId.BROWN)!!
        val three = two.with(SoundId.WIND)!!
        assertTrue(three.isFull)
        assertNull(three.with(SoundId.FAN))
        assertEquals(listOf(SoundId.RAIN, SoundId.BROWN, SoundId.WIND), three.ids)
    }

    @Test
    fun `with is idempotent for a present layer`() {
        val mix = Mix.EMPTY.with(SoundId.RAIN, 0.4f)!!
        assertSame(mix, mix.with(SoundId.RAIN, 0.9f))
    }

    @Test
    fun `without and gain keep order and clamp`() {
        val mix = Mix.of(SoundId.RAIN to 0.5f, SoundId.OCEAN to 0.5f, SoundId.FAN to 0.5f)
        assertEquals(listOf(SoundId.RAIN, SoundId.FAN), mix.without(SoundId.OCEAN).ids)
        assertEquals(1f, mix.gain(SoundId.FAN, 4f).layer(SoundId.FAN)!!.gain)
        assertEquals(0f, mix.gain(SoundId.FAN, -1f).layer(SoundId.FAN)!!.gain)
        assertFalse(mix.without(SoundId.RAIN).contains(SoundId.RAIN))
    }

    @Test
    fun `invariants are enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            Mix(listOf(SoundLayer(SoundId.RAIN), SoundLayer(SoundId.RAIN)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Mix(SoundId.entries.take(4).map { SoundLayer(it) })
        }
        assertThrows(IllegalArgumentException::class.java) { SoundLayer(SoundId.RAIN, 1.2f) }
    }

    @Test
    fun `title joins names`() {
        assertEquals("Nothing playing", Mix.EMPTY.title())
        assertEquals("Choose a sound", Mix.EMPTY.title("Choose a sound"))
        assertEquals("Rain + Brown noise", Mix.of(SoundId.RAIN to 0.5f, SoundId.BROWN to 0.5f).title())
    }

    @Test
    fun `sound keys round-trip and categories partition the catalog`() {
        SoundId.entries.forEach { assertEquals(it, SoundId.fromKey(it.key)) }
        assertNull(SoundId.fromKey("nope"))
        val partitioned = SoundCategory.entries.flatMap { SoundId.inCategory(it) }
        assertEquals(SoundId.entries.toSet(), partitioned.toSet())
        assertEquals(SoundId.entries.size, partitioned.size)
    }

    @Test
    fun `scenes are valid mixes and match order-insensitively`() {
        Scenes.all.forEach { scene ->
            assertFalse(scene.mix.isEmpty, scene.id)
            assertEquals(scene, Scenes.byId(scene.id))
            assertEquals(scene, Scenes.matching(Mix(scene.mix.layers.reversed())))
        }
        assertEquals(Scenes.all.size, Scenes.all.map { it.id }.distinct().size)
        assertNull(Scenes.matching(Mix.EMPTY))
        assertNull(Scenes.byId("missing"))
    }
}
