package dev.jtiisto.noise.core.audio

import dev.jtiisto.noise.core.audio.testing.RenderHarness
import dev.jtiisto.noise.core.audio.testing.SignalAnalysis
import dev.jtiisto.noise.core.audio.testing.WavWriter
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The offline listening harness.
 *
 * Set `NOISE_RENDER_DIR` and this writes 20 s of every catalog sound to a
 * 16-bit stereo WAV, plus one two-layer mix. Without the variable it is
 * skipped, so CI never pays for it.
 *
 * The audio is rendered *through [MixRenderer]* rather than straight from the
 * generators, deliberately: that is what the user will hear, complete with the
 * slider-squared gain mapping and the soft clipper. Rendering the raw
 * generators would let a mixing bug survive every listening session.
 */
class RenderSamplesTest {

    @Test
    @DisplayName("writes a 20 s WAV of every sound when NOISE_RENDER_DIR is set")
    fun renderEverySound() {
        val directory: String? = System.getenv(ENV_VAR)
        assumeTrue(!directory.isNullOrBlank(), "set $ENV_VAR to render WAV samples")

        val output = File(requireNotNull(directory))
        output.mkdirs()
        val config = EngineConfig(sampleRate = SAMPLE_RATE, blockFrames = BLOCK)

        for (id in SoundId.entries) {
            write(output, id.key, Mix.of(id to 1f), config)
        }
        write(
            output,
            "mix_rain_brown",
            Mix.of(SoundId.RAIN to 0.7f, SoundId.BROWN to 0.7f),
            config,
        )

        println("wrote ${SoundId.entries.size + 1} WAV files to ${output.absolutePath}")
    }

    private fun write(directory: File, name: String, mix: Mix, config: EngineConfig) {
        val renderer = MixRenderer(config, RenderHarness.SEED)
        renderer.setMix(mix)
        renderer.setMasterVolume(1f)
        renderer.start()
        // Skip the 1.5 s start fade and let every filter settle, so the file
        // is 20 s of the steady sound rather than 20 s minus a fade-in.
        RenderHarness.advance(renderer, SETTLE_SECONDS, SAMPLE_RATE)
        val capture = RenderHarness.renderMix(renderer, DURATION_SECONDS, SAMPLE_RATE)

        val file = File(directory, "$name.wav")
        WavWriter.write(file, capture.left, capture.right, SAMPLE_RATE)
        println(
            "%-16s %6.2f dBFS RMS, peak %.3f -> %s".format(
                name,
                SignalAnalysis.rmsDb(capture.left),
                SignalAnalysis.peak(capture.left),
                file.name,
            ),
        )
    }

    private companion object {
        const val ENV_VAR = "NOISE_RENDER_DIR"
        const val SAMPLE_RATE = 48_000
        const val BLOCK = 2048
        const val DURATION_SECONDS = 20.0
        const val SETTLE_SECONDS = 3.0
    }
}
