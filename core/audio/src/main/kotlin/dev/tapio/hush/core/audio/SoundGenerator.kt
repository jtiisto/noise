package dev.tapio.hush.core.audio

/**
 * A stereo sound source. Implementations own all their state, allocate
 * nothing inside [render], and are calibrated to -20 dBFS RMS per channel at
 * unity gain (see specs/audio-engine.md).
 */
interface SoundGenerator {
    /** Writes [frames] samples into [left] and [right] (overwrites, does not accumulate). */
    fun render(left: FloatArray, right: FloatArray, frames: Int)

    /** Re-seeds every internal random stream; state is otherwise reset to warm defaults. */
    fun reset(seed: Long)
}
