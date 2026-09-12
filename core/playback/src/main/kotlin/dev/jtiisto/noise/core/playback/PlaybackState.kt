package dev.jtiisto.noise.core.playback

import dev.jtiisto.noise.core.model.Mix

/** User-adjustable behaviour that survives restarts. See specs/playback.md. */
data class PlaybackSettings(
    /** Sleep-timer fade window. One of [FADE_OPTIONS_SECONDS]. */
    val fadeOutSeconds: Int = 45,
    /** true = never request audio focus, so the mix can sit under another app's audio. */
    val mixWithOtherApps: Boolean = false,
    /** Last chosen timer length, pre-selected in the timer sheet. */
    val lastTimerMinutes: Int = 30,
) {
    companion object {
        val FADE_OPTIONS_SECONDS = listOf(15, 30, 45, 60, 120)
        val TIMER_PRESETS_MINUTES = listOf(15, 30, 45, 60, 90, 120)
        const val TIMER_MIN_MINUTES = 5
        const val TIMER_MAX_MINUTES = 480
    }
}

data class TimerState(
    val totalMillis: Long,
    val endAtEpochMillis: Long,
    val remainingMillis: Long,
    val fadeMillis: Long,
) {
    val isFading: Boolean get() = remainingMillis <= fadeMillis
    val progress: Float get() = if (totalMillis <= 0) 0f else (1f - remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
}

data class PlaybackState(
    val mix: Mix = Mix.EMPTY,
    val isPlaying: Boolean = false,
    val masterVolume: Float = 0.8f,
    val timer: TimerState? = null,
    val settings: PlaybackSettings = PlaybackSettings(),
    val isDucked: Boolean = false,
)

enum class ToggleResult { ADDED, REMOVED, REJECTED_LIMIT }
