package dev.jtiisto.noise.core.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The derived properties the UI binds to. */
class TimerStateTest {

    private fun timer(remaining: Long, total: Long = 60_000L, fade: Long = 45_000L) =
        TimerState(totalMillis = total, endAtEpochMillis = 0L, remainingMillis = remaining, fadeMillis = fade)

    @Test
    fun `isFading flips when the remaining time reaches the fade window`() {
        assertFalse(timer(remaining = 45_001L).isFading)
        assertTrue(timer(remaining = 45_000L).isFading)
        assertTrue(timer(remaining = 0L).isFading)
    }

    @Test
    fun `progress runs from zero to one`() {
        assertEquals(0f, timer(remaining = 60_000L).progress)
        assertEquals(0.5f, timer(remaining = 30_000L).progress)
        assertEquals(1f, timer(remaining = 0L).progress)
    }

    @Test
    fun `progress is clamped and safe for a zero-length timer`() {
        assertEquals(0f, timer(remaining = 10L, total = 0L).progress)
        assertEquals(0f, timer(remaining = 90_000L, total = 60_000L).progress)
    }
}
