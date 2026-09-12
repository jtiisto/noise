package dev.jtiisto.noise.core.audio.generators

import dev.jtiisto.noise.core.audio.testing.RenderHarness
import dev.jtiisto.noise.core.audio.testing.SignalAnalysis
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs

/**
 * The loudness gate.
 *
 * Every generator must land at -20 dBFS RMS per channel at unity gain
 * (crickets at -26, because tonal content at equal RMS sounds much louder), so
 * that switching sounds in the middle of the night never changes how loud the
 * phone is. These are measured over long windows after a warm-up, because
 * several sounds are event-driven and a short window would mostly measure
 * which events happened to land in it.
 */
class GeneratorCalibrationTest {

    @Test
    @DisplayName("every generator is calibrated to its target RMS")
    fun everyGeneratorHitsItsTargetLevel() {
        val failures = StringBuilder()
        println("sound            target   L dBFS   R dBFS   peak   L/R corr")
        for (id in SoundId.entries) {
            val window = windowSeconds(id)
            val target = targetDb(id)
            val tolerance = tolerance(id)
            val capture = RenderHarness.renderGenerator(
                RenderHarness.generator(id),
                seconds = window,
                warmUpSeconds = WARM_UP_SECONDS,
            )
            val leftDb = SignalAnalysis.rmsDb(capture.left)
            val rightDb = SignalAnalysis.rmsDb(capture.right)
            val peak = maxOf(SignalAnalysis.peak(capture.left), SignalAnalysis.peak(capture.right))
            val correlation = SignalAnalysis.correlation(capture.left, capture.right)
            println(
                "%-16s %6.1f  %7.2f  %7.2f  %5.3f  %8.3f".format(
                    id.key, target, leftDb, rightDb, peak, correlation,
                ),
            )
            if (abs(leftDb - target) > tolerance) {
                failures.append("${id.key} left ${"%.2f".format(leftDb)} dBFS (target $target)\n")
            }
            if (abs(rightDb - target) > tolerance) {
                failures.append("${id.key} right ${"%.2f".format(rightDb)} dBFS (target $target)\n")
            }
        }
        assertEquals("", failures.toString(), "generators outside their calibration window:\n$failures")
    }

    @ParameterizedTest
    @EnumSource(SoundId::class)
    @DisplayName("output is finite and inside full scale")
    fun outputIsFiniteAndBounded(id: SoundId) {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(id),
            seconds = 8.0,
            warmUpSeconds = WARM_UP_SECONDS,
        )
        assertTrue(SignalAnalysis.allFinite(capture.left), "${id.key}: NaN or Inf on the left")
        assertTrue(SignalAnalysis.allFinite(capture.right), "${id.key}: NaN or Inf on the right")
        val peak = maxOf(SignalAnalysis.peak(capture.left), SignalAnalysis.peak(capture.right))
        assertTrue(peak <= 1f, "${id.key}: peak $peak exceeds full scale")
        // At -20 dBFS RMS a crest factor of 19 dB is the practical ceiling.
        // The low-frequency sounds (ocean swell, brown-based beds) genuinely
        // reach it because Gaussian low-frequency content peaks at 4 sigma;
        // anything above would mean a layer is spiking and eating the whole
        // mix's headroom rather than merely using its own.
        assertTrue(peak <= 0.9f, "${id.key}: peak $peak leaves no headroom for a three-layer mix")
    }

    @ParameterizedTest
    @EnumSource(SoundId::class)
    @DisplayName("left and right are genuinely decorrelated")
    fun channelsAreDecorrelated(id: SoundId) {
        val capture = RenderHarness.renderGenerator(
            RenderHarness.generator(id),
            seconds = 10.0,
            warmUpSeconds = WARM_UP_SECONDS,
        )
        val correlation = abs(SignalAnalysis.correlation(capture.left, capture.right))
        // Rain and campfire are dominated by discrete panned events, which are
        // by construction shared between the channels; the spec allows them a
        // looser bound.
        val limit = when (id) {
            SoundId.RAIN, SoundId.HEAVY_RAIN, SoundId.THUNDERSTORM, SoundId.CAMPFIRE,
            SoundId.CRICKETS,
            -> 0.95
            else -> 0.90
        }
        assertTrue(correlation < limit, "${id.key}: L/R correlation $correlation >= $limit")
    }

    @ParameterizedTest
    @EnumSource(SoundId::class)
    @DisplayName("reset with the same seed reproduces the same audio")
    fun resetIsDeterministic(id: SoundId) {
        val generator = RenderHarness.generator(id)
        generator.reset(4242L)
        val first = RenderHarness.renderGenerator(generator, seconds = 1.0)
        generator.reset(4242L)
        val second = RenderHarness.renderGenerator(generator, seconds = 1.0)
        assertTrue(
            first.left.contentEquals(second.left) && first.right.contentEquals(second.right),
            "${id.key}: reset(seed) is not reproducible",
        )
    }

    private companion object {
        const val WARM_UP_SECONDS = 2.0

        /**
         * Long windows for the sounds whose level is driven by rare events:
         * the ocean's swell is up to 15 s long and thunder arrives once a
         * minute, so a 12 s window would measure luck rather than calibration.
         */
        fun windowSeconds(id: SoundId): Double = when (id) {
            SoundId.OCEAN -> 60.0
            SoundId.THUNDERSTORM -> 40.0
            SoundId.CRICKETS -> 25.0
            SoundId.WIND, SoundId.CAMPFIRE -> 20.0
            else -> 12.0
        }

        fun targetDb(id: SoundId): Double = if (id == SoundId.CRICKETS) -26.0 else -20.0

        fun tolerance(id: SoundId): Double = when (id) {
            SoundId.CRICKETS -> 1.5
            else -> 1.0
        }
    }
}
