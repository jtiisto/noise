package dev.jtiisto.noise.ui.critter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * Drives the vector drawing off-device.
 *
 * The screenshot references judge how each critter *looks*; this proves the
 * drawing code *runs* — every [CritterKind] in both play states, across the
 * breathe wave and, crucially, across the whole secondary-motion clock (so the
 * blink windows, the ear twitch, the spout and the "z" trail all execute) —
 * against a stubbed canvas, so a bad arc, a NaN or an unhandled kind fails here
 * in the JVM suite rather than only in a render. A relaxed mock makes every
 * `DrawScope`/`Path`/transform call a no-op, so no Android graphics backend is
 * needed.
 */
class CritterDrawTest {

    @Test
    fun `every critter renders across play states, breathe and the full clock`() {
        val canvas = mockk<DrawScope>(relaxed = true)
        val path = mockk<Path>(relaxed = true)
        val accent = Color(0xFF6C7BFF)

        // i/50 lands exactly on the blink/twitch centers (0.40, 0.50, 0.60),
        // so both the open and the closed branch of every blink execute.
        val clockSamples = (0..50).map { it / 50f }

        CritterKind.entries.forEach { kind ->
            listOf(0f, 0.5f, 1f).forEach { breathe ->
                clockSamples.forEach { clock ->
                    listOf(true, false).forEach { playing ->
                        assertDoesNotThrow(
                            { canvas.drawCritter(kind, breathe, clock, playing, accent, path) },
                            "$kind should render (breathe=$breathe, clock=$clock, playing=$playing)",
                        )
                    }
                }
            }
        }
    }
}
