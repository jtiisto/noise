package dev.jtiisto.noise.ui.preview

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.playback.PlaybackController
import dev.jtiisto.noise.core.playback.PlaybackSettings
import dev.jtiisto.noise.core.playback.PlaybackState
import dev.jtiisto.noise.core.playback.TimerState
import dev.jtiisto.noise.core.playback.ToggleResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * An in-memory [PlaybackController] with no audio and no clock.
 *
 * It lives in `src/main` rather than a test source set on purpose: `@Preview`
 * functions, the screenshot tests and the unit tests all drive the UI through
 * the same stand-in, so what a reference PNG shows is what a preview shows.
 *
 * It reproduces the *public* rules — the three-layer limit, toggle results,
 * "an empty mix cannot play", gain and volume clamping, timer bookkeeping,
 * settings — and nothing else. There is deliberately no ticking: a timer here
 * holds whatever remaining time it was given.
 */
class FakePlaybackController(
    initialState: PlaybackState = PlaybackState(),
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaybackController {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun play() {
        _state.update { if (it.mix.isEmpty) it else it.copy(isPlaying = true) }
    }

    override fun pause() {
        // The real controller drops the sleep timer on a manual pause.
        _state.update { it.copy(isPlaying = false, timer = null) }
    }

    override fun togglePlay() {
        if (_state.value.isPlaying) pause() else play()
    }

    override fun toggleSound(id: SoundId): ToggleResult {
        val current = _state.value.mix
        return if (current.contains(id)) {
            val next = current.without(id)
            _state.update { it.copy(mix = next, isPlaying = it.isPlaying && !next.isEmpty) }
            ToggleResult.REMOVED
        } else {
            val next = current.with(id) ?: return ToggleResult.REJECTED_LIMIT
            _state.update { it.copy(mix = next) }
            ToggleResult.ADDED
        }
    }

    override fun setLayerGain(id: SoundId, gain: Float) {
        _state.update { it.copy(mix = it.mix.gain(id, gain)) }
    }

    override fun setMix(mix: Mix) {
        _state.update { it.copy(mix = mix, isPlaying = it.isPlaying && !mix.isEmpty) }
    }

    override fun clearMix() {
        _state.update { it.copy(mix = Mix.EMPTY, isPlaying = false, timer = null) }
    }

    override fun setMasterVolume(volume: Float) {
        _state.update { it.copy(masterVolume = volume.coerceIn(0f, 1f)) }
    }

    override fun startTimer(minutes: Int) {
        val clamped = minutes.coerceIn(
            PlaybackSettings.TIMER_MIN_MINUTES,
            PlaybackSettings.TIMER_MAX_MINUTES,
        )
        _state.update { current ->
            val total = clamped * 60_000L
            current.copy(
                isPlaying = current.isPlaying || !current.mix.isEmpty,
                settings = current.settings.copy(lastTimerMinutes = clamped),
                timer = TimerState(
                    totalMillis = total,
                    endAtEpochMillis = clock() + total,
                    remainingMillis = total,
                    fadeMillis = current.settings.fadeOutSeconds * 1_000L,
                ),
            )
        }
    }

    override fun cancelTimer() {
        _state.update { it.copy(timer = null) }
    }

    override fun playUntilCancelled() {
        _state.update { current ->
            current.copy(
                isPlaying = current.isPlaying || !current.mix.isEmpty,
                settings = current.settings.copy(lastTimerMinutes = PlaybackSettings.TIMER_UNTIL_CANCELLED),
                timer = null,
            )
        }
    }

    override fun updateSettings(transform: (PlaybackSettings) -> PlaybackSettings) {
        _state.update { current ->
            val settings = transform(current.settings)
            current.copy(
                settings = settings,
                // A new fade window applies to the timer already running.
                timer = current.timer?.copy(fadeMillis = settings.fadeOutSeconds * 1_000L),
            )
        }
    }
}
