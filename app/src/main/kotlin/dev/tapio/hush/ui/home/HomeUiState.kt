package dev.tapio.hush.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId

/** Which modal sheet, if any, is on screen. Purely local UI state. */
@Immutable
sealed interface HomeSheet {
    data object None : HomeSheet
    data object Timer : HomeSheet
    data object Settings : HomeSheet

    /** The long-press detail sheet for one catalog entry. */
    data class Sound(val id: SoundId) : HomeSheet
}

/**
 * A snackbar to show once. [id] increments on every message so that the same
 * text twice in a row still re-triggers the host.
 */
@Immutable
data class UiMessage(val id: Long, @param:StringRes val textRes: Int)

@Immutable
data class HomeUiState(
    val sheet: HomeSheet = HomeSheet.None,
    val message: UiMessage? = null,
    /**
     * The report the previous run left behind, if there is one. It stays here
     * — and keeps the Settings row visible — until the user deletes it.
     */
    val crashReport: String? = null,
    /** The notice under the header: gone for good once shared or dismissed. */
    val crashNoticeVisible: Boolean = false,
)

/**
 * Everything the Home screen can ask for. A stable interface rather than a
 * bag of lambdas: the composables take one parameter that never changes
 * identity, so passing callbacks down through the tree costs no recomposition.
 */
@Stable
interface HomeActions {
    fun onPlayPauseClick()
    fun onSoundClick(id: SoundId)
    fun onSoundLongClick(id: SoundId)
    fun onLayerGainChange(id: SoundId, gain: Float)
    fun onRemoveLayer(id: SoundId)
    fun onClearMix()
    fun onSceneClick(mix: Mix)
    fun onMasterVolumeChange(volume: Float)

    fun onTimerPillClick()
    fun onSettingsClick()
    fun onSheetDismiss()

    fun onStartTimer(minutes: Int)

    /** Drop any sleep timer and keep playing until the user stops it. */
    fun onPlayUntilCancelled()
    fun onCancelTimer()
    fun onFadeSecondsChange(seconds: Int)
    fun onMixWithOtherAppsChange(enabled: Boolean)

    /** The snackbar host has finished showing [messageId]. */
    fun onMessageShown(messageId: Long)

    /** The share sheet is open; the notice has done its job. */
    fun onShareCrashReport()

    /** Throw the report away. */
    fun onDismissCrashReport()
}
