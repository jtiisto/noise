package dev.tapio.hush.core.audio

import dev.tapio.hush.core.model.Mix

/**
 * The playback engine contract. See specs/audio-engine.md.
 *
 * All methods are safe to call from any thread and return immediately; the
 * engine applies them on its own audio thread with smoothed gain changes.
 */
interface AudioEngine {
    val isRunning: Boolean

    /** Idempotent. Creates the output and fades in from silence. */
    fun start()

    /** Idempotent. Short fade-out, then the output is released. */
    fun stop()

    /** Replaces the layer set; new layers fade in, removed layers fade out. */
    fun setMix(mix: Mix)

    /** 0..1, smoothed. Applied on top of the per-layer gains. */
    fun setMasterVolume(volume: Float)

    /** Transient duck (e.g. a notification sound is playing). */
    fun setDucked(ducked: Boolean)

    /**
     * Sleep-timer fade: gain goes to zero over [durationMs] (equal-power),
     * then [onComplete] is invoked exactly once on an arbitrary thread and the
     * engine stops itself. Calling [cancelFadeOut] before completion restores
     * gain smoothly and [onComplete] is never invoked.
     */
    fun beginFadeOut(durationMs: Long, onComplete: () -> Unit)

    fun cancelFadeOut()
}

data class EngineConfig(
    val sampleRate: Int = 48_000,
    /** ~43 ms at 48 kHz: large blocks mean fewer wakeups, which is battery on modest phones. */
    val blockFrames: Int = 2048,
    val fadeInMs: Int = 1500,
    val fadeOutMs: Int = 600,
    val layerCrossfadeMs: Int = 400,
    val duckGain: Float = 0.2f,
) {
    init {
        require(sampleRate in 8_000..192_000)
        require(blockFrames in 64..16_384)
        require(duckGain in 0f..1f)
    }
}
