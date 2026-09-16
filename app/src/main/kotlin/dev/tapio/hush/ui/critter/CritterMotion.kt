package dev.tapio.hush.ui.critter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round

private const val PIf = PI.toFloat()

/**
 * A smooth, occasional pulse in `0..1`, derived from the looping [clock].
 *
 * It is `0` for almost the whole cycle, eases up to `1` at [center] and back
 * down over a window [width] wide (a fraction of the cycle), and repeats every
 * loop — a blink or a twitch, and never a strobe. The window wraps around the
 * `0..1` loop, so a [center] near an edge still eases symmetrically. Pure and
 * allocation-free; costs one `cos`. Shared by the critter scenes
 * ([dev.tapio.hush.ui.critterscenes]) for blinks, twitches and glimmers.
 */
internal fun pulse(clock: Float, center: Float, width: Float): Float {
    val raw = clock - center
    val d = raw - round(raw)                 // signed distance to center in (-0.5, 0.5]
    val half = width / 2f
    if (abs(d) >= half) return 0f
    // Hann window: 1 at the center, 0 (with zero slope) at both edges.
    return 0.5f * (1f + cos(PIf * (d / half)))
}
