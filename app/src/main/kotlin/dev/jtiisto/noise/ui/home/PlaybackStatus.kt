package dev.jtiisto.noise.ui.home

import dev.jtiisto.noise.core.playback.PlaybackState

/**
 * The one-line status under the orb, as a decision rather than a string.
 *
 * Kept apart from the composable so the rule — which of six things the app is
 * doing wins when several are true at once — is a pure function that can be
 * unit tested. The composable only turns the answer into a resource.
 */
enum class PlaybackStatus {
    /** Nothing chosen yet. */
    EMPTY,

    /** A mix is loaded but silent. */
    PAUSED,

    /** The sleep timer has entered its fade window; the seconds left are shown. */
    FADING,

    /** A sleep timer is running; the whole minutes left are shown. */
    TIMER,

    /** Another app is speaking over us. */
    DUCKED,

    /** Playing with no sleep timer at all — it stops when the user says so. */
    UNTIL_CANCELLED,
}

/**
 * Order matters: the fade is the most urgent thing to report, then the timer
 * it belongs to, then ducking (temporary and self-explanatory), and only then
 * the steady state of playing with nothing set to stop it.
 */
fun playbackStatus(state: PlaybackState): PlaybackStatus {
    val timer = state.timer
    return when {
        state.mix.isEmpty -> PlaybackStatus.EMPTY
        !state.isPlaying -> PlaybackStatus.PAUSED
        timer != null && timer.isFading -> PlaybackStatus.FADING
        timer != null -> PlaybackStatus.TIMER
        state.isDucked -> PlaybackStatus.DUCKED
        else -> PlaybackStatus.UNTIL_CANCELLED
    }
}
