package dev.jtiisto.noise.core.playback

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of truth for what the app is doing. The UI, the
 * foreground service and the media session all observe [state]; only this
 * object changes it. All methods are main-thread safe and non-blocking.
 * See specs/playback.md for the rules.
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /** No-op when the mix is empty or audio focus is refused. */
    fun play()
    fun pause()
    fun togglePlay()

    fun toggleSound(id: SoundId): ToggleResult
    fun setLayerGain(id: SoundId, gain: Float)

    /** Replaces the mix (scenes). Keeps playing if playing; pauses when [mix] is empty. */
    fun setMix(mix: Mix)
    fun clearMix()

    fun setMasterVolume(volume: Float)

    /** Starts (or restarts) the sleep timer and remembers [minutes] as the last choice. Starts playback if paused. */
    fun startTimer(minutes: Int)
    fun cancelTimer()

    /**
     * Indefinite playback: cancels any sleep timer, remembers
     * [PlaybackSettings.TIMER_UNTIL_CANCELLED] as the last timer choice, and
     * starts playback if the mix is non-empty. The mix then plays until the
     * user pauses it.
     */
    fun playUntilCancelled()

    fun updateSettings(transform: (PlaybackSettings) -> PlaybackSettings)
}
