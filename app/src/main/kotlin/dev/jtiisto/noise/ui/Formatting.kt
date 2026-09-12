package dev.jtiisto.noise.ui

import kotlin.math.roundToInt

/**
 * Number → text helpers. Pure and locale-independent on purpose: these are
 * clock digits and percentages, not prose, and they are unit tested.
 */

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600

/**
 * A running countdown: `27:31`, or `1:04:09` once an hour or more is left.
 *
 * Rounds up, so a timer started at 30 minutes reads `30:00` for its first
 * second rather than flicking straight to `29:59`.
 */
fun formatCountdown(remainingMillis: Long): String {
    val totalSeconds = ceilSeconds(remainingMillis)
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
    } else {
        "$minutes:${twoDigits(seconds)}"
    }
}

/**
 * Whole minutes still to run — what the status line says.
 *
 * Rounds *down* so it agrees with the countdown pill beside it: 27:31 left
 * reads "sleep timer 27 min", not 28. Never reports 0 while a timer is still
 * running.
 */
fun remainingMinutes(remainingMillis: Long): Int =
    (ceilSeconds(remainingMillis) / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)

/** A timer length as a readout: `45 min`, `1 h`, `2 h 30 min`. */
fun formatDuration(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hours = safe / 60
    val mins = safe % 60
    return when {
        hours == 0 -> "$safe min"
        mins == 0 -> "$hours h"
        else -> "$hours h $mins min"
    }
}

/**
 * A fade window as a segmented-control label: `15s` … `60s`, `2 min`.
 * Kept in seconds up to a minute inclusive, as the spec's control reads.
 */
fun formatFadeSeconds(seconds: Int): String =
    if (seconds <= SECONDS_PER_MINUTE) "${seconds}s" else formatDuration(seconds / SECONDS_PER_MINUTE)

/** A 0..1 gain as a whole percentage, for labels and slider semantics. */
fun formatPercent(value: Float): String = "${percentOf(value)}%"

/** A 0..1 gain as a whole number 0..100. */
fun percentOf(value: Float): Int = (value.coerceIn(0f, 1f) * 100f).roundToInt()

private fun ceilSeconds(millis: Long): Long =
    if (millis <= 0L) 0L else (millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND

private fun twoDigits(value: Long): String = if (value < 10) "0$value" else value.toString()
