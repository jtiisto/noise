package dev.tapio.hush.ui.theme

import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AccentTest {

    @Test
    fun `hues wrap into a single turn`() {
        assertEquals(0f, normalizeHue(0f))
        assertEquals(200f, normalizeHue(200f))
        assertEquals(0f, normalizeHue(360f))
        assertEquals(10f, normalizeHue(370f))
        assertEquals(350f, normalizeHue(-10f))
        assertEquals(180f, normalizeHue(-900f))
    }

    @Test
    fun `an accent is the same colour every time it is asked for`() {
        assertEquals(accentColor(205f), accentColor(205f))
        assertEquals(accentColor(205f), accentColor(565f))
        assertNotEquals(accentColor(205f), accentColor(25f))
    }

    @Test
    fun `out-of-range saturation and lightness are clamped rather than rejected`() {
        assertEquals(accentColor(90f, saturation = 1f), accentColor(90f, saturation = 4f))
        assertEquals(accentColor(90f, lightness = 0f), accentColor(90f, lightness = -1f))
    }

    @Test
    fun `every catalog sound has a legible accent`() {
        SoundId.entries.forEach { id ->
            val color = accentFor(id)
            assertTrue(color.alpha == 1f) { "$id accent must be opaque" }
            // Light enough to carry an icon and a border on the night ground.
            assertTrue(color.luminance() > 0.15f) { "$id accent is too dark: $color" }
        }
    }

    @Test
    fun `grey noise is painted neutral, not red`() {
        val grey = accentFor(SoundId.GREY)
        val spread = maxOf(grey.red, grey.green, grey.blue) - minOf(grey.red, grey.green, grey.blue)
        assertTrue(spread < 0.1f) { "grey noise should be near-neutral, was $grey (spread $spread)" }
        // Its raw hue of 0 would otherwise make it the reddest thing on screen.
        assertNotEquals(accentColor(SoundId.GREY.hue), grey)
    }

    @Test
    fun `hue blending averages around the wheel, not through it`() {
        // 350 and 10 are both red; the arithmetic mean would be cyan.
        assertHue(0f, blendHues(listOf(350f, 10f)))
        assertHue(200f, blendHues(listOf(190f, 210f)))
    }

    @Test
    fun `hue blending ignores the order of its inputs`() {
        val hues = listOf(205f, 250f, 25f)
        assertHue(blendHues(hues)!!, blendHues(hues.reversed()))
        assertHue(blendHues(hues)!!, blendHues(hues.shuffled()))
    }

    @Test
    fun `hue blending gives up when the hues cancel out`() {
        assertNull(blendHues(emptyList()))
        assertNull(blendHues(listOf(0f, 180f)))
        assertNull(blendHues(listOf(90f, 210f, 330f)))
    }

    @Test
    fun `a single hue blends to itself`() {
        assertHue(137f, blendHues(listOf(137f)))
    }

    @Test
    fun `an empty mix falls back to the calm blue-violet dusk`() {
        assertEquals(MixPalette.Default, mixPalette(Mix.EMPTY))
    }

    @Test
    fun `one sound owns the accent and both glows keep some spread`() {
        val palette = mixPalette(Mix.of(SoundId.OCEAN to 0.8f))
        assertEquals(accentFor(SoundId.OCEAN), palette.accent)
        assertEquals(accentFor(SoundId.OCEAN), palette.glowTop)
        assertNotEquals(palette.glowTop, palette.glowBottom)
    }

    @Test
    fun `three sounds put the first and last hue in the glows and their mean in the accent`() {
        val mix = Mix.of(
            SoundId.RAIN to 0.7f,
            SoundId.THUNDERSTORM to 0.5f,
            SoundId.BROWN to 0.4f,
        )
        val palette = mixPalette(mix)

        assertEquals(accentFor(SoundId.RAIN), palette.glowTop)
        assertEquals(accentFor(SoundId.BROWN), palette.glowBottom)

        val meanHue = blendHues(listOf(SoundId.RAIN.hue, SoundId.THUNDERSTORM.hue, SoundId.BROWN.hue))!!
        assertEquals(accentColor(meanHue), palette.accent)
        // The point of the circular mean: the accent stays a colour.
        val accent = palette.accent
        val spread = maxOf(accent.red, accent.green, accent.blue) - minOf(accent.red, accent.green, accent.blue)
        assertTrue(spread > 0.2f) { "a three-hue accent should not desaturate to grey, was $accent" }
    }

    @Test
    fun `a neutral sound does not drag the blended hue`() {
        val withGrey = mixPalette(Mix.of(SoundId.OCEAN to 0.7f, SoundId.GREY to 0.4f))
        assertEquals(accentColor(SoundId.OCEAN.hue), withGrey.accent)
    }

    @Test
    fun `the remaining hued sound decides the accent when the rest are neutral`() {
        val palette = mixPalette(Mix.of(SoundId.GREY to 0.7f, SoundId.WHITE to 0.4f))
        assertEquals(accentColor(SoundId.WHITE.hue), palette.accent)
    }

    @Test
    fun `opposing hues fall back to the lead sound rather than an arbitrary mean`() {
        // Crickets (95 deg) and violet noise (270 deg) sit almost opposite each
        // other, so their vector mean is meaningless; the first layer leads.
        val palette = mixPalette(Mix.of(SoundId.CRICKETS to 0.6f, SoundId.VIOLET to 0.6f))
        assertNull(blendHues(listOf(SoundId.CRICKETS.hue, SoundId.VIOLET.hue)))
        assertEquals(accentFor(SoundId.CRICKETS), palette.accent)
    }

    private fun assertHue(expected: Float, actual: Float?) {
        requireNotNull(actual) { "expected a hue near $expected, got null" }
        val delta = minOf(abs(expected - actual), 360f - abs(expected - actual))
        assertTrue(delta < 0.5f) { "expected hue ~$expected, was $actual" }
    }
}

private fun androidx.compose.ui.graphics.Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue
