package dev.jtiisto.noise.ui.home

import dev.jtiisto.noise.R
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.playback.PlaybackState
import dev.jtiisto.noise.ui.preview.FakePlaybackController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeViewModelTest {

    private fun viewModel(state: PlaybackState = PlaybackState()): Pair<HomeViewModel, FakePlaybackController> {
        val controller = FakePlaybackController(state)
        return HomeViewModel(controller) to controller
    }

    @Test
    fun `starts with no sheet and no message`() {
        val (vm, _) = viewModel()
        assertEquals(HomeSheet.None, vm.uiState.sheet)
        assertNull(vm.uiState.message)
    }

    @Test
    fun `tapping a sound adds it to the mix without raising a message`() {
        val (vm, controller) = viewModel()
        vm.onSoundClick(SoundId.RAIN)

        assertEquals(listOf(SoundId.RAIN), controller.state.value.mix.ids)
        assertNull(vm.uiState.message)
    }

    @Test
    fun `tapping a sound already in the mix removes it`() {
        val (vm, controller) = viewModel(PlaybackState(mix = Mix.of(SoundId.RAIN to 0.5f)))
        vm.onSoundClick(SoundId.RAIN)

        assertTrue(controller.state.value.mix.isEmpty)
    }

    @Test
    fun `a fourth sound is refused with the three-layer message`() {
        val full = Mix.of(SoundId.RAIN to 0.5f, SoundId.WIND to 0.5f, SoundId.OCEAN to 0.5f)
        val (vm, controller) = viewModel(PlaybackState(mix = full))

        vm.onSoundClick(SoundId.CAMPFIRE)

        assertEquals(full, controller.state.value.mix)
        assertEquals(R.string.snackbar_max_layers, vm.uiState.message?.textRes)
    }

    @Test
    fun `the same message twice carries a new id so the host shows it again`() {
        val full = Mix.of(SoundId.RAIN to 0.5f, SoundId.WIND to 0.5f, SoundId.OCEAN to 0.5f)
        val (vm, _) = viewModel(PlaybackState(mix = full))

        vm.onSoundClick(SoundId.CAMPFIRE)
        val first = vm.uiState.message!!
        vm.onMessageShown(first.id)
        vm.onSoundClick(SoundId.CAMPFIRE)
        val second = vm.uiState.message!!

        assertEquals(first.textRes, second.textRes)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `acknowledging a stale message id leaves the current one alone`() {
        val full = Mix.of(SoundId.RAIN to 0.5f, SoundId.WIND to 0.5f, SoundId.OCEAN to 0.5f)
        val (vm, _) = viewModel(PlaybackState(mix = full))

        vm.onSoundClick(SoundId.CAMPFIRE)
        val current = vm.uiState.message!!
        vm.onMessageShown(current.id + 99)

        assertEquals(current, vm.uiState.message)
    }

    @Test
    fun `a long press opens the detail sheet for that sound`() {
        val (vm, _) = viewModel()
        vm.onSoundLongClick(SoundId.CAMPFIRE)

        assertEquals(HomeSheet.Sound(SoundId.CAMPFIRE), vm.uiState.sheet)
    }

    @Test
    fun `the timer and settings sheets replace each other and close`() {
        val (vm, _) = viewModel()

        vm.onTimerPillClick()
        assertEquals(HomeSheet.Timer, vm.uiState.sheet)

        vm.onSettingsClick()
        assertEquals(HomeSheet.Settings, vm.uiState.sheet)

        vm.onSheetDismiss()
        assertEquals(HomeSheet.None, vm.uiState.sheet)
    }

    @Test
    fun `removing the sound a detail sheet is describing closes that sheet`() {
        val (vm, controller) = viewModel(PlaybackState(mix = Mix.of(SoundId.CAMPFIRE to 0.6f)))
        vm.onSoundLongClick(SoundId.CAMPFIRE)

        vm.onRemoveLayer(SoundId.CAMPFIRE)

        assertTrue(controller.state.value.mix.isEmpty)
        assertEquals(HomeSheet.None, vm.uiState.sheet)
    }

    @Test
    fun `removing a different sound leaves the detail sheet open`() {
        val mix = Mix.of(SoundId.CAMPFIRE to 0.6f, SoundId.WIND to 0.3f)
        val (vm, _) = viewModel(PlaybackState(mix = mix))
        vm.onSoundLongClick(SoundId.CAMPFIRE)

        vm.onRemoveLayer(SoundId.WIND)

        assertEquals(HomeSheet.Sound(SoundId.CAMPFIRE), vm.uiState.sheet)
    }

    @Test
    fun `removing a sound that is not in the mix does nothing`() {
        val (vm, controller) = viewModel(PlaybackState(mix = Mix.of(SoundId.WIND to 0.3f)))
        vm.onRemoveLayer(SoundId.CAMPFIRE)

        assertEquals(listOf(SoundId.WIND), controller.state.value.mix.ids)
    }

    @Test
    fun `starting a timer closes the sheet and arms the controller`() {
        val (vm, controller) = viewModel(PlaybackState(mix = Mix.of(SoundId.RAIN to 0.5f)))
        vm.onTimerPillClick()

        vm.onStartTimer(90)

        assertEquals(HomeSheet.None, vm.uiState.sheet)
        assertEquals(90 * 60_000L, controller.state.value.timer?.totalMillis)
        assertEquals(90, controller.state.value.settings.lastTimerMinutes)
    }

    @Test
    fun `cancelling a timer closes the sheet and clears it`() {
        val (vm, controller) = viewModel(PlaybackState(mix = Mix.of(SoundId.RAIN to 0.5f)))
        vm.onStartTimer(30)
        vm.onTimerPillClick()

        vm.onCancelTimer()

        assertEquals(HomeSheet.None, vm.uiState.sheet)
        assertNull(controller.state.value.timer)
    }

    @Test
    fun `settings changes go straight through to the controller`() {
        val (vm, controller) = viewModel()

        vm.onFadeSecondsChange(120)
        vm.onMixWithOtherAppsChange(true)

        assertEquals(120, controller.state.value.settings.fadeOutSeconds)
        assertTrue(controller.state.value.settings.mixWithOtherApps)
    }

    @Test
    fun `play, scenes, gains and master volume are pure pass-through`() {
        val (vm, controller) = viewModel(PlaybackState(mix = Mix.of(SoundId.RAIN to 0.5f)))

        vm.onPlayPauseClick()
        assertTrue(controller.state.value.isPlaying)

        vm.onLayerGainChange(SoundId.RAIN, 0.25f)
        assertEquals(0.25f, controller.state.value.mix.layer(SoundId.RAIN)?.gain)

        vm.onMasterVolumeChange(0.4f)
        assertEquals(0.4f, controller.state.value.masterVolume)

        val scene = Mix.of(SoundId.OCEAN to 0.8f, SoundId.WIND to 0.25f)
        vm.onSceneClick(scene)
        assertEquals(scene, controller.state.value.mix)

        vm.onClearMix()
        assertTrue(controller.state.value.mix.isEmpty)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun `the screen reads playback straight from the controller`() {
        val (vm, controller) = viewModel()
        assertEquals(controller.state, vm.playback)
    }
}
