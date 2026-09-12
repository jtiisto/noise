package dev.jtiisto.noise.ui.timer

import dev.jtiisto.noise.R
import dev.jtiisto.noise.core.playback.PlaybackSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The sheet's three decisions that do not need a composition to check. */
class TimerSheetLogicTest {

    private val untilCancelled = PlaybackSettings(lastTimerMinutes = PlaybackSettings.TIMER_UNTIL_CANCELLED)

    @Test
    fun `the primary button says what pressing it will do`() {
        assertEquals(
            R.string.timer_play_until_cancelled,
            timerPrimaryLabel(untilCancelled = true, isPlaying = false, isTimerRunning = false),
        )
        // Already making sound: the button confirms the mode, it does not start it.
        assertEquals(
            R.string.timer_keep_playing,
            timerPrimaryLabel(untilCancelled = true, isPlaying = true, isTimerRunning = false),
        )
        // Until-cancelled over a running timer still reads as "start playing
        // this way", because pressing it is what removes the timer.
        assertEquals(
            R.string.timer_keep_playing,
            timerPrimaryLabel(untilCancelled = true, isPlaying = true, isTimerRunning = true),
        )
        assertEquals(
            R.string.timer_start,
            timerPrimaryLabel(untilCancelled = false, isPlaying = false, isTimerRunning = false),
        )
        assertEquals(
            R.string.timer_start,
            timerPrimaryLabel(untilCancelled = false, isPlaying = true, isTimerRunning = false),
        )
        assertEquals(
            R.string.timer_update,
            timerPrimaryLabel(untilCancelled = false, isPlaying = true, isTimerRunning = true),
        )
    }

    @Test
    fun `the sheet opens on the remembered choice`() {
        assertEquals(
            TimerLengthChoice.UNTIL_CANCELLED,
            initialLengthChoice(untilCancelled, isTimerRunning = false),
        )
        assertEquals(
            TimerLengthChoice.PRESET,
            initialLengthChoice(PlaybackSettings(lastTimerMinutes = 45), isTimerRunning = false),
        )
        assertEquals(
            TimerLengthChoice.CUSTOM,
            initialLengthChoice(PlaybackSettings(lastTimerMinutes = 200), isTimerRunning = false),
        )
    }

    @Test
    fun `a running timer outranks a remembered until-cancelled preference`() {
        // Showing "Until cancelled" above a live countdown would be a lie.
        assertEquals(
            TimerLengthChoice.PRESET,
            initialLengthChoice(untilCancelled, isTimerRunning = true),
        )
    }

    @Test
    fun `the until-cancelled sentinel is not treated as a length`() {
        // 0 minutes would otherwise clamp up to the 5-minute floor and make
        // "Custom" open on a nonsense value.
        assertEquals(PlaybackSettings().lastTimerMinutes, initialMinutes(untilCancelled))
        assertEquals(45, initialMinutes(PlaybackSettings(lastTimerMinutes = 45)))
        assertEquals(200, initialMinutes(PlaybackSettings(lastTimerMinutes = 200)))
    }
}
