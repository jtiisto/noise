package dev.jtiisto.noise.ui.timer

import dev.jtiisto.noise.core.playback.PlaybackSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The custom-length slider maps 0..1 onto 5..480 minutes in 5-minute steps. */
class TimerCustomLengthTest {

    @Test
    fun `the ends of the slider are the ends of the allowed range`() {
        assertEquals(PlaybackSettings.TIMER_MIN_MINUTES, customMinutes(0f))
        assertEquals(PlaybackSettings.TIMER_MAX_MINUTES, customMinutes(1f))
        assertEquals(0f, customFraction(PlaybackSettings.TIMER_MIN_MINUTES))
        assertEquals(1f, customFraction(PlaybackSettings.TIMER_MAX_MINUTES))
    }

    @Test
    fun `every position lands on a five-minute step inside the range`() {
        var fraction = 0f
        while (fraction <= 1f) {
            val minutes = customMinutes(fraction)
            assertEquals(0, minutes % 5) { "$fraction produced $minutes, not a 5-minute step" }
            assertTrue(minutes in PlaybackSettings.TIMER_MIN_MINUTES..PlaybackSettings.TIMER_MAX_MINUTES)
            fraction += 0.001f
        }
    }

    @Test
    fun `a value that is already a step survives the round trip`() {
        listOf(5, 30, 45, 200, 300, 480).forEach { minutes ->
            assertEquals(minutes, customMinutes(customFraction(minutes)))
        }
    }

    @Test
    fun `out-of-range input is clamped rather than extrapolated`() {
        assertEquals(PlaybackSettings.TIMER_MIN_MINUTES, customMinutes(-3f))
        assertEquals(PlaybackSettings.TIMER_MAX_MINUTES, customMinutes(7f))
        assertEquals(0f, customFraction(-100))
        assertEquals(1f, customFraction(10_000))
    }
}
