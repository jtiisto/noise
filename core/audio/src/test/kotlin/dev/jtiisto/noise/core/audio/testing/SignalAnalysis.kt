package dev.jtiisto.noise.core.audio.testing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measurement utilities for the DSP tests: RMS, correlation, a radix-2 FFT and
 * a Welch periodogram, plus the spectral-slope fit the noise-colour tests
 * assert on.
 *
 * These live in test code on purpose. The engine never needs an FFT at
 * runtime, and shipping one would be dead weight in the APK.
 */
object SignalAnalysis {

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var sum = 0.0
        for (i in from until to) {
            val v = x[i].toDouble()
            sum += v * v
        }
        val n = to - from
        return if (n <= 0) 0.0 else sqrt(sum / n)
    }

    fun rmsDb(x: FloatArray, from: Int = 0, to: Int = x.size): Double =
        amplitudeToDb(rms(x, from, to))

    fun amplitudeToDb(amplitude: Double): Double =
        if (amplitude <= 1e-12) -240.0 else 20.0 * log10(amplitude)

    fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var p = 0f
        for (i in from until to) {
            val v = abs(x[i])
            if (v > p) p = v
        }
        return p
    }

    fun allFinite(x: FloatArray): Boolean {
        for (v in x) if (v.isNaN() || v.isInfinite()) return false
        return true
    }

    /** Pearson correlation of two channels; ~0 means genuinely decorrelated stereo. */
    fun correlation(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        var sa = 0.0
        var sb = 0.0
        for (i in 0 until n) {
            sa += a[i]
            sb += b[i]
        }
        val ma = sa / n
        val mb = sb / n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val den = sqrt(da * db)
        return if (den <= 1e-18) 0.0 else num / den
    }

    /** Largest absolute difference between consecutive samples — a click detector. */
    fun maxAbsDelta(x: FloatArray, from: Int = 1, to: Int = x.size): Double {
        var worst = 0.0
        for (i in max(1, from) until to) {
            val d = abs(x[i] - x[i - 1]).toDouble()
            if (d > worst) worst = d
        }
        return worst
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT. [re]/[im] must be a power of two long. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two, was $n" }
        require(im.size == n)

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            val half = len shr 1
            var block = 0
            while (block < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val uRe = re[block + k]
                    val uIm = im[block + k]
                    val pRe = re[block + k + half]
                    val pIm = im[block + k + half]
                    val vRe = pRe * curRe - pIm * curIm
                    val vIm = pRe * curIm + pIm * curRe
                    re[block + k] = uRe + vRe
                    im[block + k] = uIm + vIm
                    re[block + k + half] = uRe - vRe
                    im[block + k + half] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                block += len
            }
            len = len shl 1
        }
    }

    /**
     * Welch periodogram: Hann-windowed, 50 % overlap, averaged. Returns power
     * per bin for bins `0..fftSize/2`. Absolute scaling is irrelevant for
     * every assertion here (slopes and peak locations), so it is omitted.
     */
    fun welchPsd(x: FloatArray, fftSize: Int = 8192): DoubleArray {
        require(fftSize > 1 && (fftSize and (fftSize - 1)) == 0)
        val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2.0 * PI * it / fftSize) }
        val psd = DoubleArray(fftSize / 2 + 1)
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val hop = fftSize / 2
        var start = 0
        var segments = 0
        while (start + fftSize <= x.size) {
            for (i in 0 until fftSize) {
                re[i] = x[start + i].toDouble() * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (k in psd.indices) psd[k] += re[k] * re[k] + im[k] * im[k]
            segments++
            start += hop
        }
        check(segments > 0) { "signal shorter than one FFT frame" }
        for (k in psd.indices) psd[k] /= segments
        return psd
    }

    /**
     * Least-squares slope of the spectrum in dB per octave between [lowHz] and
     * [highHz].
     *
     * The fit runs on log-spaced *bands* rather than raw bins so that every
     * octave carries the same weight; fitting bins directly would let the top
     * octave (which holds most of the bins) dominate and would report the
     * wrong slope for any spectrum that is not perfectly straight.
     */
    fun spectralSlopeDbPerOctave(
        psd: DoubleArray,
        sampleRate: Int,
        lowHz: Double = 200.0,
        highHz: Double = 8_000.0,
        bands: Int = 24,
    ): Double {
        val fftSize = (psd.size - 1) * 2
        val binHz = sampleRate.toDouble() / fftSize
        val ratio = (highHz / lowHz).pow(1.0 / bands)
        var sumX = 0.0
        var sumY = 0.0
        var sumXy = 0.0
        var sumXx = 0.0
        var n = 0
        var lo = lowHz
        for (b in 0 until bands) {
            val hi = lo * ratio
            val k0 = max(1, kotlin.math.ceil(lo / binHz).toInt())
            val k1 = min(psd.size - 1, floor(hi / binHz).toInt())
            if (k1 >= k0) {
                var sum = 0.0
                for (k in k0..k1) sum += psd[k]
                val meanPower = sum / (k1 - k0 + 1)
                val x = ln(sqrt(lo * hi)) / ln(2.0)
                val y = 10.0 * log10(meanPower.coerceAtLeast(1e-30))
                sumX += x
                sumY += y
                sumXy += x * y
                sumXx += x * x
                n++
            }
            lo = hi
        }
        check(n >= 3) { "not enough spectral bands for a fit" }
        return (n * sumXy - sumX * sumY) / (n * sumXx - sumX * sumX)
    }

    /** Frequency of the highest PSD bin above [minHz]. */
    fun dominantFrequency(psd: DoubleArray, sampleRate: Int, minHz: Double = 20.0): Double {
        val fftSize = (psd.size - 1) * 2
        val binHz = sampleRate.toDouble() / fftSize
        var bestBin = 0
        var best = -1.0
        for (k in psd.indices) {
            if (k * binHz < minHz) continue
            if (psd[k] > best) {
                best = psd[k]
                bestBin = k
            }
        }
        return bestBin * binHz
    }

    /** Short-window RMS envelope, one value per [windowSamples] samples. */
    fun rmsEnvelope(x: FloatArray, windowSamples: Int): DoubleArray {
        val count = x.size / windowSamples
        val out = DoubleArray(count)
        for (w in 0 until count) {
            out[w] = rms(x, w * windowSamples, (w + 1) * windowSamples)
        }
        return out
    }

    /**
     * Mean spacing (in envelope frames) between local maxima that are at least
     * [minSeparation] frames apart and above [threshold]. Used to measure the
     * ocean's swell period without the generator having to report it.
     */
    fun meanPeakSpacing(envelope: DoubleArray, minSeparation: Int, threshold: Double): Double {
        var lastPeak = -1
        var sum = 0.0
        var count = 0
        var i = 1
        while (i < envelope.size - 1) {
            if (envelope[i] >= threshold &&
                envelope[i] >= envelope[i - 1] &&
                envelope[i] > envelope[i + 1]
            ) {
                if (lastPeak >= 0 && i - lastPeak >= minSeparation) {
                    sum += (i - lastPeak).toDouble()
                    count++
                    lastPeak = i
                } else if (lastPeak < 0) {
                    lastPeak = i
                }
            }
            i++
        }
        return if (count == 0) 0.0 else sum / count
    }
}
