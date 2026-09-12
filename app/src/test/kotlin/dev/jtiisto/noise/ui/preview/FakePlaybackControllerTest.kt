package dev.jtiisto.noise.ui.preview

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.playback.PlaybackSettings
import dev.jtiisto.noise.core.playback.PlaybackState
import dev.jtiisto.noise.core.playback.ToggleResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stand-in controller is what every preview, screenshot reference and UI
 * test runs against, so the rules it fakes have to be the real ones.
 */
class FakePlaybackControllerTest {

    private val rain = SoundId.RAIN
    private val wind = SoundId.WIND
    private val ocean = SoundId.OCEAN
    private val fullMix = Mix.of(rain to 0.5f, wind to 0.5f, ocean to 0.5f)

    @Test
    fun `an empty mix cannot play`() {
        val controller = FakePlaybackController()
        controller.play()
        assertFalse(controller.state.value.isPlaying)

        controller.togglePlay()
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun `play and pause toggle a non-empty mix`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f)))

        controller.togglePlay()
        assertTrue(controller.state.value.isPlaying)

        controller.togglePlay()
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun `pausing by hand drops the sleep timer`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f)))
        controller.startTimer(30)

        controller.pause()

        assertNull(controller.state.value.timer)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun `toggling reports added, removed and the three-layer limit`() {
        val controller = FakePlaybackController()

        assertEquals(ToggleResult.ADDED, controller.toggleSound(rain))
        assertEquals(ToggleResult.REMOVED, controller.toggleSound(rain))

        controller.setMix(fullMix)
        assertEquals(ToggleResult.REJECTED_LIMIT, controller.toggleSound(SoundId.CAMPFIRE))
        assertEquals(fullMix, controller.state.value.mix)
    }

    @Test
    fun `removing the last layer stops playback`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f), isPlaying = true))

        controller.toggleSound(rain)

        assertTrue(controller.state.value.mix.isEmpty)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun `a scene replaces the mix and keeps playing`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f), isPlaying = true))
        val scene = Mix.of(ocean to 0.8f, wind to 0.25f)

        controller.setMix(scene)

        assertEquals(scene, controller.state.value.mix)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun `clearing the mix stops everything`() {
        val controller = FakePlaybackController(PlaybackState(mix = fullMix, isPlaying = true))
        controller.startTimer(15)

        controller.clearMix()

        assertTrue(controller.state.value.mix.isEmpty)
        assertFalse(controller.state.value.isPlaying)
        assertNull(controller.state.value.timer)
    }

    @Test
    fun `gains and master volume are clamped`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f)))

        controller.setLayerGain(rain, 4f)
        assertEquals(1f, controller.state.value.mix.layer(rain)?.gain)

        controller.setLayerGain(rain, -1f)
        assertEquals(0f, controller.state.value.mix.layer(rain)?.gain)

        controller.setMasterVolume(9f)
        assertEquals(1f, controller.state.value.masterVolume)
    }

    @Test
    fun `starting a timer arms it, remembers the length and starts playback`() {
        var now = 1_000L
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f))) { now }

        controller.startTimer(45)

        val timer = controller.state.value.timer!!
        assertEquals(45 * 60_000L, timer.totalMillis)
        assertEquals(45 * 60_000L, timer.remainingMillis)
        assertEquals(now + 45 * 60_000L, timer.endAtEpochMillis)
        assertEquals(45_000L, timer.fadeMillis)
        assertEquals(45, controller.state.value.settings.lastTimerMinutes)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun `a timer length outside the allowed range is clamped`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f)))

        controller.startTimer(1)
        assertEquals(PlaybackSettings.TIMER_MIN_MINUTES, controller.state.value.settings.lastTimerMinutes)

        controller.startTimer(10_000)
        assertEquals(PlaybackSettings.TIMER_MAX_MINUTES, controller.state.value.settings.lastTimerMinutes)
    }

    @Test
    fun `a new fade window applies to the timer already running`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f)))
        controller.startTimer(30)

        controller.updateSettings { it.copy(fadeOutSeconds = 120) }

        assertEquals(120_000L, controller.state.value.timer?.fadeMillis)
        assertEquals(120, controller.state.value.settings.fadeOutSeconds)
    }

    @Test
    fun `playing until cancelled drops the timer, remembers the choice and starts`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f)))
        controller.startTimer(30)

        controller.playUntilCancelled()

        assertNull(controller.state.value.timer)
        assertTrue(controller.state.value.isPlaying)
        assertTrue(controller.state.value.settings.prefersUntilCancelled)
        assertEquals(
            PlaybackSettings.TIMER_UNTIL_CANCELLED,
            controller.state.value.settings.lastTimerMinutes,
        )
    }

    @Test
    fun `playing until cancelled still cannot start an empty mix`() {
        val controller = FakePlaybackController()

        controller.playUntilCancelled()

        assertFalse(controller.state.value.isPlaying)
        // The preference is still remembered, so the sheet opens on it next time.
        assertTrue(controller.state.value.settings.prefersUntilCancelled)
    }

    @Test
    fun `starting a timer again clears the until-cancelled preference`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f)))
        controller.playUntilCancelled()

        controller.startTimer(60)

        assertFalse(controller.state.value.settings.prefersUntilCancelled)
        assertEquals(60, controller.state.value.settings.lastTimerMinutes)
    }

    @Test
    fun `cancelling clears the timer but leaves playback alone`() {
        val controller = FakePlaybackController(PlaybackState(mix = Mix.of(rain to 0.5f)))
        controller.startTimer(30)

        controller.cancelTimer()

        assertNull(controller.state.value.timer)
        assertTrue(controller.state.value.isPlaying)
    }
}
