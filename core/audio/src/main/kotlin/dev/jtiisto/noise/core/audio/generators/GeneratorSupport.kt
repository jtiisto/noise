package dev.jtiisto.noise.core.audio.generators

/**
 * Shared calibration targets and seed derivation for every generator.
 *
 * ### Loudness calibration
 * Each generator multiplies its output by a hand-tuned constant so that its
 * long-term RMS at unity gain is [TARGET_RMS] (-20 dBFS). This is the single
 * most important non-obvious property of the engine: without it, switching
 * from brown noise to crickets would be a 15 dB jump in the middle of the
 * night. The constants were measured, not derived — `GeneratorCalibrationTest`
 * renders 12-30 s of each sound after a 2 s warm-up and asserts the result
 * lands within 1 dB of target, so a synthesis change that alters level fails
 * the build instead of surprising the listener.
 *
 * Crickets are the one exception ([TONAL_TARGET_RMS], -26 dBFS): they are
 * narrow-band and tonal, and equal RMS against a broadband bed reads as much
 * louder. This matches the spec.
 */
internal const val TARGET_RMS = 0.1f

/** -26 dBFS, for tonal sounds where equal RMS would sound far too loud. */
internal const val TONAL_TARGET_RMS = 0.0501187f

/**
 * Derives an independent, well-distributed stream seed from a generator seed
 * and a small salt. Uses SplitMix64's finaliser: adjacent salts must not
 * produce correlated xorshift states, or the left and right channels of a
 * generator would partly track each other.
 */
internal fun streamSeed(seed: Long, salt: Int): Long {
    var z = seed + salt * -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}
