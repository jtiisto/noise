package dev.jtiisto.noise.core.playback

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Sleep timer: ticking, the fade window, completion and cancellation. */
class PlaybackTimerTest {

    private val minute = 60_000L

    @Test
    fun `startTimer starts playback when paused and the mix is non-empty`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)

        f.controller.startTimer(30)

        assertTrue(f.state.isPlaying)
        assertEquals(1, f.engine.startCount)
        val timer = checkNotNull(f.state.timer)
        assertEquals(30 * minute, timer.totalMillis)
        assertEquals(30 * minute, timer.remainingMillis)
        assertEquals(VirtualClock.EPOCH_START + 30 * minute, timer.endAtEpochMillis)
    }

    @Test
    fun `startTimer remembers the choice and clamps out-of-range values`() = playbackTest { f ->
        f.controller.startTimer(10_000)
        assertEquals(PlaybackSettings.TIMER_MAX_MINUTES, f.state.settings.lastTimerMinutes)

        f.controller.startTimer(1)
        assertEquals(PlaybackSettings.TIMER_MIN_MINUTES, f.state.settings.lastTimerMinutes)
    }

    @Test
    fun `playUntilCancelled clears the timer, remembers the choice and plays`() = playbackTest { f ->
        f.controller.setMix(Mix.of(SoundId.RAIN to 0.5f))
        f.controller.startTimer(30)
        assertNotNull(f.state.timer)

        f.controller.playUntilCancelled()

        assertNull(f.state.timer)
        assertTrue(f.state.isPlaying)
        assertEquals(PlaybackSettings.TIMER_UNTIL_CANCELLED, f.state.settings.lastTimerMinutes)
        assertTrue(f.state.settings.prefersUntilCancelled)
        assertEquals(1, f.engine.startCount)
        assertEquals(PlaybackSettings.TIMER_UNTIL_CANCELLED, checkNotNull(f.store.last).settings.lastTimerMinutes)
    }

    @Test
    fun `playUntilCancelled during the fade restores gain and keeps playing`() = playbackTest { f ->
        f.controller.setMix(Mix.of(SoundId.RAIN to 0.5f))
        f.controller.startTimer(5)
        advanceThrough(5 * minute - 30_000L) // inside the 45 s fade window
        assertEquals(1, f.engine.fadeStartCount)

        f.controller.playUntilCancelled()

        assertEquals(1, f.engine.cancelFadeCount)
        assertNull(f.state.timer)
        assertTrue(f.state.isPlaying)
    }

    @Test
    fun `playUntilCancelled with an empty mix only records the preference`() = playbackTest { f ->
        f.controller.playUntilCancelled()

        assertFalse(f.state.isPlaying)
        assertEquals(0, f.engine.startCount)
        assertEquals(PlaybackSettings.TIMER_UNTIL_CANCELLED, f.state.settings.lastTimerMinutes)
    }

    @Test
    fun `startTimer with an empty mix arms the timer but does not play`() = playbackTest { f ->
        f.controller.startTimer(30)

        assertFalse(f.state.isPlaying)
        assertEquals(0, f.engine.startCount)
        assertNotNull(f.state.timer)
    }

    @Test
    fun `the timer counts down once a second`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(30)

        advanceThrough(5_000)

        assertEquals(30 * minute - 5_000, f.state.timer!!.remainingMillis)
    }

    @Test
    fun `restarting the timer replaces the running one`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(15)
        advanceThrough(5_000)

        f.controller.startTimer(45)

        val timer = checkNotNull(f.state.timer)
        assertEquals(45 * minute, timer.totalMillis)
        assertEquals(45 * minute, timer.remainingMillis)
        assertEquals(45, f.state.settings.lastTimerMinutes)
    }

    @Test
    fun `the fade starts exactly once when the window opens`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(5) // 300 s, default 45 s fade

        advanceThrough(255_000)
        assertEquals(1, f.engine.fadeStartCount)
        assertEquals(45_000L, f.engine.fadeDurations.single())
        assertTrue(f.state.timer!!.isFading)

        advanceThrough(40_000)
        assertEquals(1, f.engine.fadeStartCount)
    }

    @Test
    fun `the engine fade completion pauses, clears the timer and persists`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(5)
        advanceThrough(255_000)

        f.engine.completeFade()

        assertFalse(f.state.isPlaying)
        assertNull(f.state.timer)
        assertEquals(1, f.engine.stopCount)
        assertEquals(1, f.focus.abandonCount)
        val saved = checkNotNull(f.store.last)
        assertFalse(saved.wasPlaying)
        assertEquals(0L, saved.timerEndAtEpochMillis)
    }

    @Test
    fun `cancelling mid-fade cancels the engine fade and keeps playing`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(5)
        advanceThrough(255_000)

        f.controller.cancelTimer()

        assertEquals(1, f.engine.cancelFadeCount)
        assertNull(f.state.timer)
        assertTrue(f.state.isPlaying)
        assertEquals(0, f.engine.stopCount)
    }

    @Test
    fun `cancelling before the fade window does not touch the engine fade`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(30)
        advanceThrough(5_000)

        f.controller.cancelTimer()

        assertEquals(0, f.engine.cancelFadeCount)
        assertEquals(0, f.engine.fadeStartCount)
        assertNull(f.state.timer)
    }

    @Test
    fun `cancelTimer without a timer is a no-op`() = playbackTest { f ->
        f.controller.cancelTimer()

        assertNull(f.state.timer)
        assertTrue(f.store.saves.isEmpty())
    }

    @Test
    fun `a longer fade window set mid-timer opens the window immediately`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(5)
        advanceThrough(200_000) // 100 s left, outside the default 45 s window
        assertEquals(0, f.engine.fadeStartCount)

        f.controller.updateSettings { it.copy(fadeOutSeconds = 120) }

        assertEquals(120_000L, f.state.timer!!.fadeMillis)
        assertEquals(1, f.engine.fadeStartCount)
        // Faded over what is actually left, not the nominal 120 s window.
        assertEquals(100_000L, f.engine.fadeDurations.single())
    }

    @Test
    fun `a shorter fade window set mid-timer delays the fade`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(5)

        f.controller.updateSettings { it.copy(fadeOutSeconds = 15) }
        advanceThrough(255_000) // would have faded under the default 45 s window

        assertEquals(0, f.engine.fadeStartCount)
        advanceThrough(30_000)
        assertEquals(1, f.engine.fadeStartCount)
        assertEquals(15_000L, f.engine.fadeDurations.single())
    }

    @Test
    fun `resuming inside the fade window re-issues the fade`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(5)
        advanceThrough(255_000)
        assertEquals(1, f.engine.fadeStartCount)

        f.focus.emit(FocusEvent.LOSS_TRANSIENT)
        advanceThrough(15_000)
        f.focus.emit(FocusEvent.GAIN)

        assertTrue(f.state.isPlaying)
        // Without this the mix would jump back to full volume and then cut out.
        assertEquals(2, f.engine.fadeStartCount)
        assertEquals(30_000L, f.engine.fadeDurations.last())
    }

    @Test
    fun `a manual pause cancels the timer`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(30)

        f.controller.pause()

        assertNull(f.state.timer)
        assertFalse(f.state.isPlaying)
    }

    @Test
    fun `a timer that runs out while paused still completes`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(5)
        // A permanent focus loss pauses without cancelling the wall-clock timer.
        f.focus.emit(FocusEvent.LOSS)
        assertFalse(f.state.isPlaying)

        advanceThrough(300_000)

        assertNull(f.state.timer)
        assertEquals(0, f.engine.fadeStartCount) // nothing was playing to fade
    }
}
