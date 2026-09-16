package dev.tapio.hush.core.audio.generators

import dev.tapio.hush.core.audio.testing.RenderHarness
import dev.tapio.hush.core.audio.testing.SignalAnalysis
import dev.tapio.hush.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The definition of each noise colour is its spectral slope, so that is what
 * gets asserted: a least-squares fit of the Welch periodogram in dB against
 * log2(frequency), between 200 Hz and 8 kHz.
 *
 * The band matters. Below 200 Hz brown's high-pass and pink's lowest pole bend
 * the curve, and above 8 kHz blue and violet meet their 16 kHz tilt filter;
 * inside the band every colour should be a straight line, and the tolerance is
 * 1 dB/oct — tight enough that a broken filter cannot pass, loose enough to
 * absorb the ~0.2 dB error from running Kellet's 44.1 kHz coefficients at
 * 48 kHz.
 */
class NoiseColourSpectrumTest {

    @Test
    @DisplayName("each noise colour has its defining spectral slope")
    fun coloursHaveTheirSlopes() {
        val expected = mapOf(
            SoundId.WHITE to 0.0,
            SoundId.PINK to -3.0,
            SoundId.BROWN to -6.0,
            SoundId.BLUE to 3.0,
            SoundId.VIOLET to 6.0,
        )
        val failures = StringBuilder()
        println("colour   expected  measured dB/oct")
        for ((id, target) in expected) {
            val slope = measureSlope(id)
            println("%-8s %8.1f  %8.2f".format(id.key, target, slope))
            if (abs(slope - target) > TOLERANCE_DB_PER_OCTAVE) {
                failures.append("${id.key}: ${"%.2f".format(slope)} dB/oct, expected $target\n")
            }
        }
        assertEquals("", failures.toString(), "spectral slopes out of tolerance:\n$failures")
    }

    @Test
    @DisplayName("grey noise is tilted, not flat, and boosts both ends")
    fun greyIsInverseAWeighted() {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(SoundId.GREY),
            seconds = 20.0,
            warmUpSeconds = 1.0,
        )
        val psd = SignalAnalysis.welchPsd(capture.left, FFT_SIZE)
        val low = bandPowerDb(psd, 80.0, 160.0)
        val mid = bandPowerDb(psd, 2_500.0, 3_500.0)
        val high = bandPowerDb(psd, 9_000.0, 13_000.0)
        println("grey band powers: low=%.2f mid=%.2f high=%.2f dB".format(low, mid, high))
        // Inverse A-weighting: both extremes must sit clearly above the
        // presence region the ear is most sensitive to.
        org.junit.jupiter.api.Assertions.assertTrue(
            low - mid > 8.0,
            "grey: low end only ${"%.1f".format(low - mid)} dB above the 3 kHz dip",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            high - mid > 4.0,
            "grey: high end only ${"%.1f".format(high - mid)} dB above the 3 kHz dip",
        )
    }

    private fun measureSlope(id: SoundId): Double {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(id),
            seconds = 20.0,
            warmUpSeconds = 1.0,
        )
        val psd = SignalAnalysis.welchPsd(capture.left, FFT_SIZE)
        return SignalAnalysis.spectralSlopeDbPerOctave(
            psd,
            capture.sampleRate,
            LOW_HZ,
            HIGH_HZ,
        )
    }

    private fun bandPowerDb(psd: DoubleArray, lowHz: Double, highHz: Double): Double {
        val fftSize = (psd.size - 1) * 2
        val binHz = RenderHarness.SAMPLE_RATE.toDouble() / fftSize
        var sum = 0.0
        var count = 0
        for (k in psd.indices) {
            val f = k * binHz
            if (f in lowHz..highHz) {
                sum += psd[k]
                count++
            }
        }
        return 10.0 * kotlin.math.log10(sum / count)
    }

    private companion object {
        const val FFT_SIZE = 8192
        const val LOW_HZ = 200.0
        const val HIGH_HZ = 8_000.0
        const val TOLERANCE_DB_PER_OCTAVE = 1.0
    }
}
