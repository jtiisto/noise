package dev.jtiisto.noise.core.playback

import dev.jtiisto.noise.core.model.Mix

/** User-adjustable behaviour that survives restarts. See specs/playback.md. */
data class PlaybackSettings(
    /** Sleep-timer fade window. One of [FADE_OPTIONS_SECONDS]. */
    val fadeOutSeconds: Int = 45,
    /** true = never request audio focus, so the mix can sit under another app's audio. */
    val mixWithOtherApps: Boolean = false,
    /**
     * Last chosen timer length, pre-selected in the timer sheet.
     * [TIMER_UNTIL_CANCELLED] (0) means the user last chose to play with no
     * timer at all; any other value is within [TIMER_MIN_MINUTES]..[TIMER_MAX_MINUTES].
     */
    val lastTimerMinutes: Int = 30,
) {
    val prefersUntilCancelled: Boolean get() = lastTimerMinutes == TIMER_UNTIL_CANCELLED

    companion object {
        /** Sentinel for [lastTimerMinutes]: no sleep timer, play until the user stops it. */
        const val TIMER_UNTIL_CANCELLED = 0
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
