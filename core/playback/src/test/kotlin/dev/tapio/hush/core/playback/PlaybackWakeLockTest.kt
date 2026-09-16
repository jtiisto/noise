package dev.tapio.hush.core.playback

import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wake lock must be held exactly while the engine renders — acquired when
 * playback starts, released when it stops — so overnight playback survives deep
 * sleep without the lock ever being leaked. Every transition that starts or
 * stops the engine is checked here.
 */
class PlaybackWakeLockTest {

    private val minute = 60_000L

    @Test
    fun `held while playing, released on pause`() = playbackTest { f ->
        f.controller.setMix(Mix.of(SoundId.RAIN to 0.6f))
        f.controller.play()
        assertTrue(f.wakeLock.held)
        assertEquals(1, f.wakeLock.acquireCount)

        f.controller.pause()
        assertFalse(f.wakeLock.held)
        assertEquals(1, f.wakeLock.releaseCount)
    }

    @Test
    fun `never acquired when play is refused for an empty mix`() = playbackTest { f ->
        f.controller.play()
        assertFalse(f.wakeLock.held)
        assertEquals(0, f.wakeLock.acquireCount)
    }

    @Test
    fun `released only when focus is denied, never acquired`() = playbackTest { f ->
        f.focus.granted = false
        f.controller.setMix(Mix.of(SoundId.RAIN to 0.6f))
        f.controller.play()
        assertFalse(f.state.isPlaying)
        assertFalse(f.wakeLock.held)
        assertEquals(0, f.wakeLock.acquireCount)
    }

    @Test
    fun `held through the sleep-timer fade and released at completion`() = playbackTest { f ->
        f.controller.setMix(Mix.of(SoundId.RAIN to 0.6f))
        f.controller.startTimer(5)
        assertTrue(f.wakeLock.held)

        // Advance into the 45 s fade window; the engine is still rendering, so
        // the lock must stay held.
        advanceThrough(5 * minute - 30_000L)
        assertTrue(f.wakeLock.held)

        // The engine finishes the fade and fires its completion callback.
        f.engine.completeFade()
        assertFalse(f.wakeLock.held)
        assertEquals(1, f.wakeLock.releaseCount)
    }

    @Test
    fun `released on a transient focus loss and re-acquired on gain`() = playbackTest { f ->
        f.controller.setMix(Mix.of(SoundId.RAIN to 0.6f))
        f.controller.play()
        assertTrue(f.wakeLock.held)

        f.focus.emit(FocusEvent.LOSS_TRANSIENT)
        assertFalse(f.wakeLock.held)

        f.focus.emit(FocusEvent.GAIN)
        assertTrue(f.wakeLock.held)
        assertEquals(2, f.wakeLock.acquireCount)
    }

    @Test
    fun `released when the mix is cleared`() = playbackTest { f ->
        f.controller.setMix(Mix.of(SoundId.RAIN to 0.6f))
        f.controller.play()
        assertTrue(f.wakeLock.held)

        f.controller.clearMix()
        assertFalse(f.wakeLock.held)
    }
}
