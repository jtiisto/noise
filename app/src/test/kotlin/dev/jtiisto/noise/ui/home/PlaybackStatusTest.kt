package dev.jtiisto.noise.ui.home

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.playback.PlaybackState
import dev.jtiisto.noise.core.playback.TimerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackStatusTest {

    private val mix = Mix.of(SoundId.RAIN to 0.6f)

    private fun timer(remainingMillis: Long, fadeMillis: Long = 45_000L) = TimerState(
        totalMillis = 30 * 60_000L,
        endAtEpochMillis = 0L,
        remainingMillis = remainingMillis,
        fadeMillis = fadeMillis,
    )

    @Test
    fun `an empty mix reports nothing chosen, whatever else is set`() {
        assertEquals(PlaybackStatus.EMPTY, playbackStatus(PlaybackState()))
        assertEquals(
            PlaybackStatus.EMPTY,
            playbackStatus(PlaybackState(isPlaying = true, timer = timer(60_000))),
        )
    }

    @Test
    fun `a silent mix reports paused before anything else`() {
        assertEquals(
            PlaybackStatus.PAUSED,
            playbackStatus(PlaybackState(mix = mix, isPlaying = false, timer = timer(60_000))),
        )
    }

    @Test
    fun `playing with no timer is the until-cancelled state`() {
        assertEquals(
            PlaybackStatus.UNTIL_CANCELLED,
            playbackStatus(PlaybackState(mix = mix, isPlaying = true)),
        )
    }

    @Test
    fun `a running timer outranks the until-cancelled state`() {
        assertEquals(
            PlaybackStatus.TIMER,
            playbackStatus(PlaybackState(mix = mix, isPlaying = true, timer = timer(10 * 60_000))),
        )
    }

    @Test
    fun `the fade outranks the timer it belongs to`() {
        assertEquals(
            PlaybackStatus.FADING,
            playbackStatus(PlaybackState(mix = mix, isPlaying = true, timer = timer(30_000))),
        )
    }

    @Test
    fun `ducking is reported only when nothing more urgent is happening`() {
        assertEquals(
            PlaybackStatus.DUCKED,
            playbackStatus(PlaybackState(mix = mix, isPlaying = true, isDucked = true)),
        )
        assertEquals(
            PlaybackStatus.TIMER,
            playbackStatus(
                PlaybackState(mix = mix, isPlaying = true, isDucked = true, timer = timer(10 * 60_000)),
            ),
        )
    }
}
