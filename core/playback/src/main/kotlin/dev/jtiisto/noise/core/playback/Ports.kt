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


/**
 * A partial wake lock held exactly while the engine is rendering. The
 * foreground service keeps the *process* alive, but not the CPU: once a phone
 * enters deep sleep with the screen off the audio thread can be descheduled,
 * the AudioTrack buffer drains and playback stalls. Holding a partial wake
 * lock for the duration of playback is what keeps an eight-hour mix going
 * overnight (the same thing ExoPlayer does via setWakeMode). Both calls are
 * idempotent; the controller acquires when the engine starts and releases when
 * it stops, so the lock mirrors playback and is never leaked.
 */
interface WakeLock {
    fun acquire()
    fun release()
}

/** Wall-clock source, injected so the sleep timer is testable in virtual time. */
interface Clock {
    /** Epoch milliseconds. */
    fun now(): Long
}
