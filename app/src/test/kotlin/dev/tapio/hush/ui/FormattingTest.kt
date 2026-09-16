package dev.tapio.hush.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FormattingTest {

    @ParameterizedTest(name = "{0} ms counts down as {1}")
    @CsvSource(
        "0, 0:00",
        "1, 0:01",
        "999, 0:01",
        "1000, 0:01",
        "1001, 0:02",
        "59000, 0:59",
        "60000, 1:00",
        "1651000, 27:31",
        "3599000, 59:59",
        "3600000, 1:00:00",
        "3661000, 1:01:01",
        "28800000, 8:00:00",
    )
    fun `formats the countdown`(millis: Long, expected: String) {
        assertEquals(expected, formatCountdown(millis))
    }

    @Test
    fun `a negative remainder reads as zero rather than a negative clock`() {
        assertEquals("0:00", formatCountdown(-5_000))
    }

    @ParameterizedTest(name = "{0} ms is {1} whole minutes")
    @CsvSource(
        "1651000, 27", // 27:31 — floors, so it agrees with the pill beside it
        "60000, 1",
        "119000, 1",
        // Agrees with the pill, which ceils to 2:00 at this point.
        "119999, 2",
        "120000, 2",
        "1, 1", // never announces a running timer as 0 min
        "0, 1",
    )
    fun `reports whole minutes remaining`(millis: Long, expected: Int) {
        assertEquals(expected, remainingMinutes(millis))
    }

    @ParameterizedTest(name = "{0} minutes reads as {1}")
    @CsvSource(
        "5, 5 min",
        "45, 45 min",
        "59, 59 min",
        "60, 1 h",
        "90, 1 h 30 min",
        "200, 3 h 20 min",
        "480, 8 h",
    )
    fun `formats a duration`(minutes: Int, expected: String) {
        assertEquals(expected, formatDuration(minutes))
    }

    @ParameterizedTest(name = "a {0} s fade reads as {1}")
    @CsvSource(
        "15, 15s",
        "30, 30s",
        "45, 45s",
        "60, 60s",
        "120, 2 min",
    )
    fun `formats the fade window`(seconds: Int, expected: String) {
        assertEquals(expected, formatFadeSeconds(seconds))
    }

    @Test
    fun `formats percentages and clamps out-of-range gains`() {
        assertEquals("0%", formatPercent(0f))
        assertEquals("50%", formatPercent(0.5f))
        assertEquals("72%", formatPercent(0.72f))
        assertEquals("100%", formatPercent(1f))
        assertEquals("100%", formatPercent(4f))
        assertEquals("0%", formatPercent(-1f))
    }
}
