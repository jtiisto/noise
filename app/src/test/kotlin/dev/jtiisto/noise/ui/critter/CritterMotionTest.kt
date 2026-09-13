package dev.jtiisto.noise.ui.critter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure motion maths behind the secondary animations. These are the only
 * non-Composable helpers the critter adds, so they carry their own tests; the
 * drawing that consumes them is exercised by [CritterDrawTest].
 */
class CritterMotionTest {

    @Test
    fun `pulse peaks at its center`() {
        assertEquals(1f, pulse(clock = 0.5f, center = 0.5f, width = 0.1f), 1e-4f)
    }

    @Test
    fun `pulse is zero outside the window`() {
        // Center 0.5, width 0.1 -> non-zero only within (0.45, 0.55).
        assertEquals(0f, pulse(0.0f, 0.5f, 0.1f))
        assertEquals(0f, pulse(0.3f, 0.5f, 0.1f))
        assertEquals(0f, pulse(0.9f, 0.5f, 0.1f))
    }

    @Test
    fun `pulse is zero at the window edges`() {
        assertEquals(0f, pulse(0.45f, 0.5f, 0.1f), 1e-4f)
        assertEquals(0f, pulse(0.55f, 0.5f, 0.1f), 1e-4f)
    }

    @Test
    fun `pulse stays within 0 and 1 and eases (no jump) across the window`() {
        var prev = pulse(0.45f, 0.5f, 0.1f)
        var x = 0.451f
        while (x <= 0.5f) {
            val v = pulse(x, 0.5f, 0.1f)
            assertTrue(v in 0f..1f, "pulse out of range at $x: $v")
            // Rising toward the center, and smoothly (small step).
            assertTrue(v >= prev - 1e-3f, "pulse should rise toward center at $x")
            assertTrue(v - prev < 0.2f, "pulse jumped at $x")
            prev = v
            x += 0.001f
        }
    }

    @Test
    fun `pulse is symmetric about its center`() {
        assertEquals(pulse(0.47f, 0.5f, 0.1f), pulse(0.53f, 0.5f, 0.1f), 1e-4f)
    }

    @Test
    fun `pulse wraps around the loop for a center near an edge`() {
        // Center at 0.0: the window straddles the 0/1 seam, so clock 0.98 and
        // 0.02 are the same small distance from the center and blink equally.
        val a = pulse(0.98f, 0.0f, 0.1f)
        val b = pulse(0.02f, 0.0f, 0.1f)
        assertTrue(a > 0f, "just before the seam should be inside the window")
        assertEquals(a, b, 1e-4f)
        assertEquals(1f, pulse(1.0f, 0.0f, 0.1f), 1e-4f) // 1.0 wraps to the center
    }
}
