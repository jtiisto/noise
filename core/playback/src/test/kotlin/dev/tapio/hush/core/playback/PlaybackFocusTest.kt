package dev.tapio.hush.core.playback

import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Audio-focus sequences. See specs/playback.md. */
class PlaybackFocusTest {

    private fun PlaybackFixture.startPlaying() {
        controller.toggleSound(SoundId.RAIN)
        controller.play()
        check(state.isPlaying)
    }

    @Test
    fun `permanent loss pauses and a later gain does not resume`() = playbackTest { f ->
        f.startPlaying()

        f.focus.emit(FocusEvent.LOSS)

        assertFalse(f.state.isPlaying)
        assertEquals(1, f.engine.stopCount)
        assertEquals(1, f.focus.abandonCount)

        f.focus.emit(FocusEvent.GAIN)

        assertFalse(f.state.isPlaying)
        assertEquals(1, f.engine.startCount)
    }

    @Test
    fun `transient loss keeps focus and gain resumes`() = playbackTest { f ->
        f.startPlaying()

        f.focus.emit(FocusEvent.LOSS_TRANSIENT)

        assertFalse(f.state.isPlaying)
        assertEquals(1, f.engine.stopCount)
        // Focus is held through a transient loss, otherwise GAIN never reaches us.
        assertEquals(0, f.focus.abandonCount)

        f.focus.emit(FocusEvent.GAIN)

        assertTrue(f.state.isPlaying)
        assertEquals(2, f.engine.startCount)
        // We still held focus, so no second request was needed.
        assertEquals(1, f.focus.requestCount)
    }

    @Test
    fun `transient loss while already paused does not arm a resume`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)

        f.focus.emit(FocusEvent.LOSS_TRANSIENT)
        f.focus.emit(FocusEvent.GAIN)

        assertFalse(f.state.isPlaying)
        assertEquals(0, f.engine.startCount)
    }

    @Test
    fun `duck lowers the engine and gain restores it`() = playbackTest { f ->
        f.startPlaying()

        f.focus.emit(FocusEvent.DUCK)

        assertTrue(f.state.isDucked)
        assertTrue(f.engine.ducked)
        assertTrue(f.state.isPlaying) // ducking never pauses

        f.focus.emit(FocusEvent.GAIN)

        assertFalse(f.state.isDucked)
        assertFalse(f.engine.ducked)
        assertEquals(listOf(true, false), f.engine.duckCalls)
        assertEquals(1, f.engine.startCount) // no spurious restart
    }

    @Test
    fun `pausing while ducked unducks`() = playbackTest { f ->
        f.startPlaying()
        f.focus.emit(FocusEvent.DUCK)

        f.controller.pause()

        assertFalse(f.state.isDucked)
        assertFalse(f.engine.ducked)
    }

    @Test
    fun `becoming noisy pauses and never resumes`() = playbackTest { f ->
        f.startPlaying()

        f.focus.emit(FocusEvent.BECOMING_NOISY)

        assertFalse(f.state.isPlaying)
        assertEquals(1, f.focus.abandonCount)

        f.focus.emit(FocusEvent.GAIN)

        assertFalse(f.state.isPlaying)
    }

    @Test
    fun `a focus pause persists that we are no longer playing`() = playbackTest { f ->
        f.startPlaying()

        f.focus.emit(FocusEvent.LOSS)

        assertFalse(checkNotNull(f.store.last).wasPlaying)
    }
}
