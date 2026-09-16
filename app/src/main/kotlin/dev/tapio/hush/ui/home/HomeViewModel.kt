package dev.tapio.hush.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.tapio.hush.R
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import dev.tapio.hush.core.playback.PlaybackController
import dev.tapio.hush.core.playback.PlaybackState
import dev.tapio.hush.core.playback.ToggleResult
import dev.tapio.hush.crash.CrashReportStore
import dev.tapio.hush.crash.InMemoryCrashReportStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Home's only state of its own: which sheet is open and which message is
 * pending. Everything the user can see about playback comes straight from
 * [PlaybackController.state] — this class never mirrors or caches it.
 */
class HomeViewModel(
    private val controller: PlaybackController,
    private val crashReports: CrashReportStore = InMemoryCrashReportStore(),
) : ViewModel(), HomeActions {

    val playback: StateFlow<PlaybackState> get() = controller.state

    // The report is read once, at construction: the file only ever changes
    // while the process is dying, so re-reading it would find the same thing.
    var uiState: HomeUiState by mutableStateOf(
        crashReports.read().let { HomeUiState(crashReport = it, crashNoticeVisible = it != null) },
    )
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

    override fun onShareCrashReport() {
        // The report survives the share so the Settings row can offer it again
        // — a share sheet the user backs out of should not lose the trace.
        uiState = uiState.copy(crashNoticeVisible = false)
    }

    override fun onDismissCrashReport() {
        crashReports.clear()
        uiState = uiState.copy(crashReport = null, crashNoticeVisible = false)
    }

    /** Surfaces a message the screen did not raise itself (a denied permission, say). */
    fun postMessage(@StringRes textRes: Int) {
        lastMessageId += 1
        uiState = uiState.copy(message = UiMessage(lastMessageId, textRes))
    }
}
