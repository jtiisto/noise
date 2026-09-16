package dev.tapio.hush.core.audio.dsp

/**
 * Sample-accurate Poisson event scheduler.
 *
 * Natural event streams — raindrops on a roof, crackles in a fire, chirps
 * across a field — are Poisson processes: events are independent, so the gap
 * between them is exponentially distributed. A fixed grid with jitter sounds
 * mechanical no matter how much jitter you add, because it can never produce
 * the clusters and holes a real shower has. Drawing `-ln(U) / rate` gaps costs
 * one logarithm per *event* (tens per second), not per sample.
 *
 * [tick] is called once per sample and returns true on the samples an event
 * starts, so events land on exact sample boundaries rather than being quantised
 * to the 43 ms render block.
 */
class PoissonClock(
    private val sampleRate: Int,
    private val rng: NoiseRng,
    rateHz: Float = 1f,
) {

    private var meanGapSamples = 1f
    private var countdown = 0

    init {
        setRate(rateHz)
        countdown = drawGap()
    }

    /** Mean events per second. Values <= 0 disable the clock. */
    fun setRate(hz: Float) {
        meanGapSamples = if (hz <= 0f) Float.MAX_VALUE else sampleRate / hz
    }

    fun reset() {
        countdown = drawGap()
    }

    /** Schedules the next event between [minSeconds] and [maxSeconds] from now (uniform). */
    fun scheduleUniform(minSeconds: Float, maxSeconds: Float) {
        countdown = (rng.nextRange(minSeconds, maxSeconds) * sampleRate).toInt().coerceAtLeast(1)
    }

    /** True exactly on the samples an event fires. */
    fun tick(): Boolean {
        if (--countdown > 0) return false
        countdown = drawGap()
        return true
    }

    private fun drawGap(): Int {
        if (meanGapSamples == Float.MAX_VALUE) return Int.MAX_VALUE
        val gap = rng.nextExponential() * meanGapSamples
        return if (gap < 1f) 1 else if (gap > Int.MAX_VALUE.toFloat()) Int.MAX_VALUE else gap.toInt()
    }
}
