package dev.jtiisto.noise.core.playback

import kotlinx.coroutines.flow.Flow

/**
 * Audio-focus port. Everything the controller needs to know about the rest of
 * the phone's audio: whether we may play, and what happened to us.
 */
interface AudioFocusGate {
    /** Requests focus. Returns false when the system refuses; the caller must stay paused. */
    fun request(): Boolean

    /** Releases focus and stops listening for headphone unplugs. Idempotent. */
    fun abandon()

    /**
     * Focus changes, hot. Emissions are dropped rather than buffered forever if
     * nobody is collecting — a stale focus event is worse than no event.
     */
    val events: Flow<FocusEvent>
}

enum class FocusEvent {
    /** Permanent loss: pause, do not resume. */
    LOSS,

    /** Temporary loss (a call): pause, resume on [GAIN]. */
    LOSS_TRANSIENT,

    /** Temporary loss we may duck through (a notification). */
    DUCK,

    /** Focus is ours again. */
    GAIN,

    /** Headphones were unplugged: pause, never resume. */
    BECOMING_NOISY,
}

/**
 * Starts the foreground service that keeps the process alive while playing.
 * Idempotent — the controller calls it on every [PlaybackController.play].
 */
interface ServiceLauncher {
    fun ensureStarted()
}

/** Wall-clock source, injected so the sleep timer is testable in virtual time. */
interface Clock {
    /** Epoch milliseconds. */
    fun now(): Long
}
