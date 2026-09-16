package dev.tapio.hush.core.playback

import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cold-start race: the controller is built eagerly at Koin start and
 * `store.load()` suspends on disk, so the user can be tapping tiles before the
 * persisted state has landed. Their commands must survive it.
 */
class PlaybackColdStartTest {

    private val minute = 60_000L

    @Test
    fun `commands issued before the load returns are applied after it, not lost`() {
        val gate = CompletableDeferred<Unit>()
        playbackTest(
            persisted = PersistedState(
                mix = Mix.of(SoundId.OCEAN to 0.7f),
                masterVolume = 0.3f,
                wasPlaying = true,
            ),
            loadGate = gate,
        ) { f ->
            // Nothing from disk yet; the user is already interacting.
            assertTrue(f.state.mix.isEmpty)
            assertEquals(ToggleResult.ADDED, f.controller.toggleSound(SoundId.RAIN))
            f.controller.setMasterVolume(0.9f)
            f.controller.startTimer(30)

            // Queued, not applied: the controller must not act on half-known state.
            assertTrue(f.state.mix.isEmpty)
            assertFalse(f.state.isPlaying)
            assertNull(f.state.timer)
            assertEquals(0, f.engine.startCount)

            gate.complete(Unit)

            // The persisted "was playing" still took effect...
            assertTrue(f.state.isPlaying)
            assertEquals(1, f.engine.startCount)
            // ...but everything the user did wins over what the disk remembered.
            assertEquals(listOf(SoundId.RAIN), f.state.mix.ids)
            assertEquals(listOf(SoundId.RAIN), f.engine.mix.ids)
            assertEquals(0.9f, f.state.masterVolume)
            assertEquals(0.9f, f.engine.masterVolume)
            assertEquals(30 * minute, checkNotNull(f.state.timer).totalMillis)
        }
    }

    @Test
    fun `a queued pause beats a persisted wasPlaying`() {
        val gate = CompletableDeferred<Unit>()
        playbackTest(
            persisted = PersistedState(mix = Mix.of(SoundId.OCEAN to 0.7f), wasPlaying = true),
            loadGate = gate,
        ) { f ->
            f.controller.pause()

            gate.complete(Unit)

            assertFalse(f.state.isPlaying)
            assertEquals(1, f.engine.stopCount) // restore started it, the queued pause stopped it
        }
    }

    @Test
    fun `a timer started during the load leaves exactly one ticking timer`() {
        val gate = CompletableDeferred<Unit>()
        playbackTest(
            persisted = PersistedState(
                mix = Mix.of(SoundId.OCEAN to 0.7f),
                wasPlaying = true,
                timerEndAtEpochMillis = VirtualClock.EPOCH_START + 10 * minute,
                timerTotalMillis = 30 * minute,
            ),
            loadGate = gate,
        ) { f ->
            f.controller.startTimer(45)

            gate.complete(Unit)

            val timer = checkNotNull(f.state.timer)
            assertEquals(45 * minute, timer.totalMillis)
            assertEquals(45 * minute, timer.remainingMillis)

            // Two tick loops would produce identical remainingMillis and be
            // otherwise invisible, so count the clock reads instead: the tick
            // reads the clock exactly once a second.
            val readsBefore = f.clock.reads
            advanceThrough(5_000)
            assertEquals(5, f.clock.reads - readsBefore)
            assertEquals(45 * minute - 5_000, checkNotNull(f.state.timer).remainingMillis)
        }
    }

    @Test
    fun `restoring a persisted timer alone also leaves exactly one ticking timer`() = playbackTest(
        persisted = PersistedState(
            mix = Mix.of(SoundId.OCEAN to 0.7f),
            wasPlaying = true,
            timerEndAtEpochMillis = VirtualClock.EPOCH_START + 10 * minute,
            timerTotalMillis = 30 * minute,
        ),
    ) { f ->
        val readsBefore = f.clock.reads
        advanceThrough(3_000)

        assertEquals(3, f.clock.reads - readsBefore)
    }

    @Test
    fun `a load that fails still releases the queued commands`() {
        val gate = CompletableDeferred<Unit>()
        playbackTest(loadGate = gate, loadFailure = IllegalStateException("disk on fire")) { f ->
            f.controller.toggleSound(SoundId.RAIN)
            assertTrue(f.state.mix.isEmpty)

            gate.complete(Unit)

            // A controller that never opened the gate would be deaf for the
            // whole session - far worse than losing the persisted mix.
            assertEquals(listOf(SoundId.RAIN), f.state.mix.ids)
            // ...and the failure must not escape: the production scope has no
            // exception handler, so an escaping load error would crash the app.
            assertEquals(0, f.uncaught.size)
            assertEquals(1, (f.controller as DefaultPlaybackController).loadFailures)
            assertEquals(PlaybackSettings(), f.state.settings)
        }
    }
}
