package dev.jtiisto.noise.core.playback

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ported Notch playback fixes (2026-09-15 review): headphone-unplug monitoring
 * decoupled from focus (P3) and the sticky-restart resume ordering + retry (P5).
 */
class PlaybackNoisyAndRestoreTest {

    private val ocean = Mix.of(SoundId.OCEAN to 0.7f)

    // ---- P3: unplug monitoring follows the engine, not focus -------------------

    @Test
    fun `noisy monitoring tracks the engine even when mixing with other apps`() {
        val gate = CompletableDeferred<Unit>()
        playbackTest(
            persisted = PersistedState(mix = ocean, settings = PlaybackSettings(mixWithOtherApps = true)),
            loadGate = gate,
        ) { f ->
            gate.complete(Unit)
            f.controller.play()

            assertTrue(f.state.isPlaying)
            // "Mix with other apps" never asks for focus, but the receiver must
            // still be armed so an unplug pauses instead of blaring the speaker.
            assertEquals(0, f.focus.requestCount)
            assertTrue(f.focus.noisyMonitoring, "unplug monitoring should be on while rendering")

            f.controller.pause()
            assertFalse(f.focus.noisyMonitoring, "unplug monitoring should be off once stopped")
        }
    }

    // ---- P5: sticky-restart resume ordering + retry ----------------------------

    @Test
    fun `restore brings the service up before the focus request`() {
        val gate = CompletableDeferred<Unit>()
        playbackTest(
            persisted = PersistedState(mix = ocean, wasPlaying = true),
            loadGate = gate,
        ) { f ->
            f.focus.granted = false // the system refuses focus to a background restart
            gate.complete(Unit)

            // startServiceFirst: the service was promoted even though focus was
            // then refused. The old focus-first order returned before starting it.
            assertEquals(1, f.launcher.startCount)
            assertFalse(f.state.isPlaying)
        }
    }

    @Test
    fun `a restore whose focus was refused retries once and resumes`() {
        val gate = CompletableDeferred<Unit>()
        playbackTest(
            persisted = PersistedState(mix = ocean, wasPlaying = true),
            loadGate = gate,
        ) { f ->
            f.focus.granted = false
            gate.complete(Unit)
            assertFalse(f.state.isPlaying)

            f.focus.granted = true // the service is up now, focus is available
            advanceThrough(1_500)

            assertTrue(f.state.isPlaying, "the retry should have resumed playback")
        }
    }

    @Test
    fun `a user command stands the restore retry down`() {
        val gate = CompletableDeferred<Unit>()
        playbackTest(
            persisted = PersistedState(mix = ocean, wasPlaying = true),
            loadGate = gate,
        ) { f ->
            f.focus.granted = false
            gate.complete(Unit)
            assertFalse(f.state.isPlaying)

            f.controller.pause() // the user acted; the auto-resume must not override it
            f.focus.granted = true
            advanceThrough(1_500)

            assertFalse(f.state.isPlaying, "the retry must not resume after a user command")
        }
    }
}
