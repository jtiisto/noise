package dev.tapio.hush.core.playback

import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Debounced writes, flush points, and coming back after a process kill. */
class PlaybackPersistenceTest {

    private val minute = 60_000L

    @Test
    fun `two quick changes collapse into a single debounced save`() = playbackTest { f ->
        f.controller.setMasterVolume(0.5f)
        f.controller.setMasterVolume(0.6f)

        assertTrue(f.store.saves.isEmpty()) // nothing written yet
        advanceThrough(300)

        assertEquals(1, f.store.saves.size)
        assertEquals(0.6f, f.store.last!!.masterVolume)
    }

    @Test
    fun `a change outside the debounce window saves twice`() = playbackTest { f ->
        f.controller.setMasterVolume(0.5f)
        advanceThrough(300)
        f.controller.setMasterVolume(0.6f)
        advanceThrough(300)

        assertEquals(2, f.store.saves.size)
    }

    @Test
    fun `pause flushes immediately`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()

        f.controller.pause()

        assertEquals(1, f.store.saves.size)
        assertFalse(f.store.last!!.wasPlaying)
        assertEquals(listOf(SoundId.RAIN), f.store.last!!.mix.ids)
    }

    @Test
    fun `startTimer and cancelTimer flush immediately`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)

        f.controller.startTimer(30)

        assertEquals(1, f.store.saves.size)
        val armed = f.store.last!!
        assertTrue(armed.wasPlaying)
        assertEquals(30 * minute, armed.timerTotalMillis)
        assertEquals(VirtualClock.EPOCH_START + 30 * minute, armed.timerEndAtEpochMillis)
        assertEquals(30, armed.settings.lastTimerMinutes)

        f.controller.cancelTimer()

        assertEquals(2, f.store.saves.size)
        assertEquals(0L, f.store.last!!.timerEndAtEpochMillis)
        assertEquals(0L, f.store.last!!.timerTotalMillis)
    }

    @Test
    fun `play persists that we are playing once the debounce elapses`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()

        advanceThrough(300)

        assertEquals(1, f.store.saves.size)
        assertTrue(f.store.last!!.wasPlaying)
    }

    @Test
    fun `restore resumes a mix that was playing`() = playbackTest(
        persisted = PersistedState(
            mix = Mix.of(SoundId.RAIN to 0.4f, SoundId.BROWN to 0.9f),
            masterVolume = 0.55f,
            settings = PlaybackSettings(fadeOutSeconds = 15, mixWithOtherApps = false, lastTimerMinutes = 90),
            wasPlaying = true,
        ),
    ) { f ->
        assertEquals(1, f.store.loadCount)
        assertTrue(f.state.isPlaying)
        assertEquals(listOf(SoundId.RAIN, SoundId.BROWN), f.state.mix.ids)
        assertEquals(0.55f, f.state.masterVolume)
        assertEquals(0.55f, f.engine.masterVolume)
        assertEquals(15, f.state.settings.fadeOutSeconds)
        assertEquals(90, f.state.settings.lastTimerMinutes)
        assertEquals(1, f.launcher.startCount)
    }

    @Test
    fun `restore stays paused when nothing was playing`() = playbackTest(
        persisted = PersistedState(mix = Mix.of(SoundId.RAIN to 0.4f), wasPlaying = false),
    ) { f ->
        assertFalse(f.state.isPlaying)
        assertEquals(0, f.engine.startCount)
        assertEquals(listOf(SoundId.RAIN), f.state.mix.ids)
        assertTrue(f.store.saves.isEmpty()) // restoring must not write back
    }

    @Test
    fun `restore stays paused when the persisted mix is empty`() = playbackTest(
        persisted = PersistedState(mix = Mix.EMPTY, wasPlaying = true),
    ) { f ->
        assertFalse(f.state.isPlaying)
        assertEquals(0, f.engine.startCount)
    }

    @Test
    fun `restore re-arms a timer whose end is still in the future`() = playbackTest(
        persisted = PersistedState(
            mix = Mix.of(SoundId.OCEAN to 0.7f),
            wasPlaying = true,
            timerEndAtEpochMillis = VirtualClock.EPOCH_START + 10 * minute,
            timerTotalMillis = 30 * minute,
        ),
    ) { f ->
        assertTrue(f.state.isPlaying)
        val timer = checkNotNull(f.state.timer)
        assertEquals(10 * minute, timer.remainingMillis)
        assertEquals(30 * minute, timer.totalMillis)

        advanceThrough(60_000)
        assertEquals(9 * minute, f.state.timer!!.remainingMillis)
    }

    @Test
    fun `restore inside the fade window fades straight away`() = playbackTest(
        persisted = PersistedState(
            mix = Mix.of(SoundId.OCEAN to 0.7f),
            wasPlaying = true,
            timerEndAtEpochMillis = VirtualClock.EPOCH_START + 20_000L,
            timerTotalMillis = 30 * minute,
        ),
    ) { f ->
        assertTrue(f.state.isPlaying)
        assertEquals(1, f.engine.fadeStartCount)
        assertEquals(20_000L, f.engine.fadeDurations.single())
    }

    @Test
    fun `restore treats a timer that ended while we were dead as expired`() = playbackTest(
        persisted = PersistedState(
            mix = Mix.of(SoundId.OCEAN to 0.7f),
            wasPlaying = true,
            timerEndAtEpochMillis = VirtualClock.EPOCH_START - 1_000L,
            timerTotalMillis = 30 * minute,
        ),
    ) { f ->
        assertFalse(f.state.isPlaying)
        assertEquals(0, f.engine.startCount)
        assertNull(f.state.timer)
        // The stale timer is cleared on disk so the next launch is clean.
        val saved = checkNotNull(f.store.last)
        assertFalse(saved.wasPlaying)
        assertEquals(0L, saved.timerEndAtEpochMillis)
        assertEquals(listOf(SoundId.OCEAN), saved.mix.ids)
    }
}
