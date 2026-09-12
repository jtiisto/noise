package dev.jtiisto.noise.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.jtiisto.noise.R
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.playback.PlaybackController
import dev.jtiisto.noise.core.playback.PlaybackState
import dev.jtiisto.noise.core.playback.ToggleResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Home's only state of its own: which sheet is open and which message is
 * pending. Everything the user can see about playback comes straight from
 * [PlaybackController.state] — this class never mirrors or caches it.
 */
class HomeViewModel(
    private val controller: PlaybackController,
) : ViewModel(), HomeActions {

    val playback: StateFlow<PlaybackState> get() = controller.state

    var uiState: HomeUiState by mutableStateOf(HomeUiState())
        private set

    private var lastMessageId = 0L

    override fun onPlayPauseClick() = controller.togglePlay()

    override fun onSoundClick(id: SoundId) {
        if (controller.toggleSound(id) == ToggleResult.REJECTED_LIMIT) {
            postMessage(R.string.snackbar_max_layers)
        }
    }

    override fun onSoundLongClick(id: SoundId) {
        uiState = uiState.copy(sheet = HomeSheet.Sound(id))
    }

    override fun onLayerGainChange(id: SoundId, gain: Float) = controller.setLayerGain(id, gain)

    override fun onRemoveLayer(id: SoundId) {
        if (controller.state.value.mix.contains(id)) controller.toggleSound(id)
        // Removing the sound the detail sheet is describing closes the sheet.
        if ((uiState.sheet as? HomeSheet.Sound)?.id == id) onSheetDismiss()
    }

    override fun onClearMix() = controller.clearMix()

    override fun onSceneClick(mix: Mix) = controller.setMix(mix)

    override fun onMasterVolumeChange(volume: Float) = controller.setMasterVolume(volume)

    override fun onTimerPillClick() {
        uiState = uiState.copy(sheet = HomeSheet.Timer)
    }

    override fun onSettingsClick() {
        uiState = uiState.copy(sheet = HomeSheet.Settings)
    }

    override fun onSheetDismiss() {
        uiState = uiState.copy(sheet = HomeSheet.None)
    }

    override fun onStartTimer(minutes: Int) {
        controller.startTimer(minutes)
        onSheetDismiss()
    }

    override fun onPlayUntilCancelled() {
        controller.playUntilCancelled()
        onSheetDismiss()
    }

    override fun onCancelTimer() {
        controller.cancelTimer()
        onSheetDismiss()
    }

    override fun onFadeSecondsChange(seconds: Int) =
        controller.updateSettings { it.copy(fadeOutSeconds = seconds) }

    override fun onMixWithOtherAppsChange(enabled: Boolean) =
        controller.updateSettings { it.copy(mixWithOtherApps = enabled) }

    override fun onMessageShown(messageId: Long) {
        if (uiState.message?.id == messageId) uiState = uiState.copy(message = null)
    }

    /** Surfaces a message the screen did not raise itself (a denied permission, say). */
    fun postMessage(@StringRes textRes: Int) {
        lastMessageId += 1
        uiState = uiState.copy(message = UiMessage(lastMessageId, textRes))
    }
}
