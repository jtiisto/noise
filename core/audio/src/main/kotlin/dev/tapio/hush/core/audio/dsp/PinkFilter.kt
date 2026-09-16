package dev.tapio.hush.core.audio.dsp

/**
 * Paul Kellet's "refined" pink-noise filter: six one-pole sections plus a
 * feed-through and a one-sample-delayed term, summed.
 *
 * It approximates -3 dB/oct to within about +/-0.05 dB from 10 Hz to 20 kHz at
 * 44.1 kHz — an order of magnitude flatter than the popular three-pole
 * "economy" version, for the price of a handful of multiply-accumulates. The
 * coefficients are defined in the z domain, so at 48 kHz the pole frequencies
 * shift up by 8.8 %; the resulting slope error over our 200 Hz - 8 kHz
 * measurement band stays inside 0.2 dB, which is why we keep the published
 * constants rather than re-fitting them per sample rate.
 *
 * The `white` input is expected in `[-1, 1)`; the output RMS is roughly 3.6x
 * the input's, which each generator folds into its calibration gain.
 */
class PinkFilter {

    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    fun reset() {
        b0 = 0f
        b1 = 0f
        b2 = 0f
        b3 = 0f
        b4 = 0f
        b5 = 0f
        b6 = 0f
    }

    fun process(white: Float): Float {
        b0 = 0.99886f * b0 + white * 0.0555179f
        b1 = 0.99332f * b1 + white * 0.0750759f
        b2 = 0.96900f * b2 + white * 0.1538520f
        b3 = 0.86650f * b3 + white * 0.3104856f
        b4 = 0.55000f * b4 + white * 0.5329522f
        b5 = -0.7616f * b5 - white * 0.0168980f
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
        b6 = white * 0.115926f
        return out
    }
}
