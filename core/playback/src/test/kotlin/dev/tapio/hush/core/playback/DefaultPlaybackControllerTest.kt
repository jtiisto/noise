package dev.tapio.hush.core.playback

import app.cash.turbine.test
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.Scenes
import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Play/pause, the mix, volume and audio focus. Timer and persistence have their own suites. */
class DefaultPlaybackControllerTest {

    @Test
    fun `play with an empty mix does nothing`() = playbackTest { f ->
        f.controller.play()

        assertFalse(f.state.isPlaying)
        assertEquals(0, f.engine.startCount)
        assertEquals(0, f.focus.requestCount)
        assertEquals(0, f.launcher.startCount)
    }

    @Test
    fun `play requests focus, starts the service and drives the engine`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)

        f.controller.play()

        assertTrue(f.state.isPlaying)
        assertEquals(1, f.focus.requestCount)
        assertEquals(1, f.launcher.startCount)
        assertEquals(1, f.engine.startCount)
        assertEquals(listOf(SoundId.RAIN), f.engine.mix.ids)
        assertEquals(f.state.masterVolume, f.engine.masterVolume)
    }

    @Test
    fun `play is idempotent`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()
        f.controller.play()

        assertEquals(1, f.engine.startCount)
        assertEquals(1, f.focus.requestCount)
    }

    @Test
    fun `focus denied leaves us paused and touches nothing`() = playbackTest { f ->
        f.focus.granted = false
        f.controller.toggleSound(SoundId.RAIN)

        f.controller.play()

        assertFalse(f.state.isPlaying)
        assertEquals(1, f.focus.requestCount)
        assertEquals(0, f.engine.startCount)
        assertEquals(0, f.launcher.startCount)
    }

    @Test
    fun `mixWithOtherApps never requests focus`() = playbackTest { f ->
        f.controller.updateSettings { it.copy(mixWithOtherApps = true) }
        f.controller.toggleSound(SoundId.RAIN)

        f.controller.play()

        assertTrue(f.state.isPlaying)
        assertEquals(0, f.focus.requestCount)
    }

    @Test
    fun `turning mixWithOtherApps on while playing gives focus back`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()

        f.controller.updateSettings { it.copy(mixWithOtherApps = true) }

        assertEquals(1, f.focus.abandonCount)
        assertTrue(f.state.isPlaying)
    }

    @Test
    fun `turning mixWithOtherApps off while playing takes focus`() = playbackTest { f ->
        f.controller.updateSettings { it.copy(mixWithOtherApps = true) }
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()
        assertEquals(0, f.focus.requestCount)

        f.controller.updateSettings { it.copy(mixWithOtherApps = false) }

        assertEquals(1, f.focus.requestCount)
        assertTrue(f.state.isPlaying)
    }

    @Test
    fun `turning mixWithOtherApps off pauses when focus is refused`() = playbackTest { f ->
        f.controller.updateSettings { it.copy(mixWithOtherApps = true) }
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.startTimer(30)
        assertTrue(f.state.isPlaying)
        f.focus.granted = false

        f.controller.updateSettings { it.copy(mixWithOtherApps = false) }

        // Same rule as play(): no focus, no sound.
        assertFalse(f.state.isPlaying)
        assertNull(f.state.timer)
        assertEquals(1, f.engine.stopCount)
        val saved = checkNotNull(f.store.last)
        assertFalse(saved.wasPlaying)
        assertFalse(saved.settings.mixWithOtherApps)
    }

    @Test
    fun `pause stops the engine and abandons focus`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()

        f.controller.pause()

        assertFalse(f.state.isPlaying)
        assertEquals(1, f.engine.stopCount)
        assertEquals(1, f.focus.abandonCount)
    }

    @Test
    fun `togglePlay flips between play and pause`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)

        f.controller.togglePlay()
        assertTrue(f.state.isPlaying)

        f.controller.togglePlay()
        assertFalse(f.state.isPlaying)
        assertEquals(1, f.engine.stopCount)
    }

    @Test
    fun `toggleSound adds up to three layers then reports the limit`() = playbackTest { f ->
        assertEquals(ToggleResult.ADDED, f.controller.toggleSound(SoundId.RAIN))
        assertEquals(ToggleResult.ADDED, f.controller.toggleSound(SoundId.BROWN))
        assertEquals(ToggleResult.ADDED, f.controller.toggleSound(SoundId.WIND))

        assertEquals(ToggleResult.REJECTED_LIMIT, f.controller.toggleSound(SoundId.FAN))

        assertEquals(listOf(SoundId.RAIN, SoundId.BROWN, SoundId.WIND), f.state.mix.ids)
        assertFalse(f.state.mix.contains(SoundId.FAN))
    }

    @Test
    fun `toggleSound removes a present layer`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.toggleSound(SoundId.BROWN)

        assertEquals(ToggleResult.REMOVED, f.controller.toggleSound(SoundId.RAIN))

        assertEquals(listOf(SoundId.BROWN), f.state.mix.ids)
    }

    @Test
    fun `removing the last layer while playing pauses`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()

        f.controller.toggleSound(SoundId.RAIN)

        assertTrue(f.state.mix.isEmpty)
        assertFalse(f.state.isPlaying)
        assertEquals(1, f.engine.stopCount)
    }

    @Test
    fun `toggling a layer while playing pushes the new mix to the engine`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()

        f.controller.toggleSound(SoundId.CAMPFIRE)

        assertEquals(listOf(SoundId.RAIN, SoundId.CAMPFIRE), f.engine.mix.ids)
    }

    @Test
    fun `setLayerGain clamps, keeps order and reaches the engine`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.toggleSound(SoundId.OCEAN)
        f.controller.play()

        f.controller.setLayerGain(SoundId.RAIN, 2f)

        assertEquals(1f, f.state.mix.layer(SoundId.RAIN)!!.gain)
        assertEquals(listOf(SoundId.RAIN, SoundId.OCEAN), f.state.mix.ids)
        assertEquals(1f, f.engine.mix.layer(SoundId.RAIN)!!.gain)
    }

    @Test
    fun `setLayerGain for an absent sound is ignored`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)

        f.controller.setLayerGain(SoundId.FAN, 0.5f)

        assertEquals(listOf(SoundId.RAIN), f.state.mix.ids)
    }

    @Test
    fun `setMix applies a scene while playing without restarting the engine`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()
        val scene = Scenes.byId("seaside")!!

        f.controller.setMix(scene.mix)

        assertTrue(f.state.isPlaying)
        assertEquals(scene.mix, f.state.mix)
        assertEquals(scene.mix, f.engine.mix)
        assertEquals(1, f.engine.startCount)
        assertEquals(0, f.engine.stopCount)
    }

    @Test
    fun `setMix with an empty mix pauses`() = playbackTest { f ->
        f.controller.toggleSound(SoundId.RAIN)
        f.controller.play()

        f.controller.setMix(Mix.EMPTY)

        assertTrue(f.state.mix.isEmpty)
        assertFalse(f.state.isPlaying)
        assertEquals(1, f.engine.stopCount)
        assertEquals(1, f.focus.abandonCount)
    }

    @Test
    fun `clearMix empties and pauses`() = playbackTest { f ->
        f.controller.setMix(Scenes.byId("cabin")!!.mix)
        f.controller.play()

        f.controller.clearMix()

        assertTrue(f.state.mix.isEmpty)
        assertFalse(f.state.isPlaying)
    }

    @Test
    fun `setMasterVolume clamps and reaches the engine even when paused`() = playbackTest { f ->
        f.controller.setMasterVolume(1.4f)
        assertEquals(1f, f.state.masterVolume)
        assertEquals(1f, f.engine.masterVolume)

        f.controller.setMasterVolume(-0.2f)
        assertEquals(0f, f.state.masterVolume)
        assertEquals(0f, f.engine.masterVolume)
    }

    @Test
    fun `state emits the mix change to observers`() = playbackTest { f ->
        f.controller.state.test {
            assertTrue(awaitItem().mix.isEmpty)

            f.controller.toggleSound(SoundId.CRICKETS)

            assertEquals(listOf(SoundId.CRICKETS), awaitItem().mix.ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateSettings keeps unrelated fields`() = playbackTest { f ->
        f.controller.updateSettings { it.copy(fadeOutSeconds = 15) }

        assertEquals(15, f.state.settings.fadeOutSeconds)
        assertEquals(PlaybackSettings().lastTimerMinutes, f.state.settings.lastTimerMinutes)
        assertFalse(f.state.settings.mixWithOtherApps)
    }
}
