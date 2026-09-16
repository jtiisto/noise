package dev.tapio.hush.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turning `SoundId.hue` into the colours the UI paints with.
 *
 * Everything in this file is a pure function of its inputs so it can be unit
 * tested on the JVM: no Compose runtime, no Android, no theme lookup.
 */

/** Saturation/lightness the whole app shares, so every accent has equal weight. */
const val ACCENT_SATURATION: Float = 0.74f
const val ACCENT_LIGHTNESS: Float = 0.68f

/** How long a mix change takes to travel through the background. */
const val PALETTE_ANIMATION_MILLIS: Int = 900

/** Wraps any hue (including negatives and >360) into `[0, 360)`. */
fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

/** A hue in degrees to the app's accent colour. */
fun accentColor(
    hue: Float,
    saturation: Float = ACCENT_SATURATION,
    lightness: Float = ACCENT_LIGHTNESS,
): Color = Color.hsl(
    hue = normalizeHue(hue),
    saturation = saturation.coerceIn(0f, 1f),
    lightness = lightness.coerceIn(0f, 1f),
)

/**
 * Grey noise carries `hue = 0`, which as a colour is pure red — the one hue in
 * the catalog that would misdescribe its sound. Treat it as "no hue" and paint
 * it as a cool near-neutral instead.
 */
private val NEUTRAL_SOUNDS = setOf(SoundId.GREY)

/** The accent for one sound. */
fun accentFor(id: SoundId): Color =
    if (id in NEUTRAL_SOUNDS) accentColor(220f, saturation = 0.08f, lightness = 0.74f)
    else accentColor(id.hue)

/** The colours a given mix paints the screen with. */
@Immutable
data class MixPalette(
    /** Drives controls, selected states and the orb. */
    val accent: Color,
    /** Top-right aurora glow. */
    val glowTop: Color,
    /** Bottom-left aurora glow. */
    val glowBottom: Color,
    val backgroundTop: Color = HushColor.NightTop,
    val backgroundBottom: Color = HushColor.NightDeep,
) {
    companion object {
        /** Empty mix: a calm blue/violet dusk. */
        val Default = MixPalette(
            accent = accentColor(226f),
            glowTop = accentColor(226f),
            glowBottom = accentColor(272f),
        )
    }
}

/**
 * Circular mean of [hues], in degrees, or null when they cancel out.
 *
 * Hues live on a wheel, so neither of the obvious averages works: the
 * arithmetic mean of 350 and 10 is 180 (cyan, not red), and averaging the
 * *colours* in RGB desaturates — three well-spread hues come out grey, which
 * is exactly what a three-layer mix would produce. Summing unit vectors keeps
 * the answer on the wheel and at full saturation. Two opposite hues have no
 * meaningful mean; that is the null.
 */
fun blendHues(hues: List<Float>): Float? {
    if (hues.isEmpty()) return null
    var x = 0.0
    var y = 0.0
    for (hue in hues) {
        val radians = normalizeHue(hue) * Math.PI / 180.0
        x += cos(radians)
        y += sin(radians)
    }
    if (hypot(x, y) / hues.size < HUE_CANCELLATION_THRESHOLD) return null
    return normalizeHue((atan2(y, x) * 180.0 / Math.PI).toFloat())
}

/** Below this resultant length the hues have effectively cancelled out. */
private const val HUE_CANCELLATION_THRESHOLD = 0.05

/**
 * The palette for [mix]. Up to three hues contribute: the first sound owns the
 * top glow, the last owns the bottom, and the accent is the blend of them all.
 * A single sound still gets two glows — the second is nudged around the wheel
 * so the background keeps some depth.
 */
fun mixPalette(mix: Mix): MixPalette {
    val ids = mix.ids
    return when (ids.size) {
        0 -> MixPalette.Default
        1 -> {
            val only = ids[0]
            MixPalette(
                accent = accentFor(only),
                glowTop = accentFor(only),
                glowBottom = accentColor(only.hue + SINGLE_HUE_SPREAD_DEGREES),
            )
        }
        else -> MixPalette(
            // Neutral sounds carry no hue, so they must not drag the mean.
            accent = blendHues(ids.filterNot { it in NEUTRAL_SOUNDS }.map { it.hue })
                ?.let(::accentColor)
                ?: accentFor(ids.first()),
            glowTop = accentFor(ids.first()),
            glowBottom = accentFor(ids.last()),
        )
    }
}

/** How far the second glow of a one-sound mix sits from the first. */
private const val SINGLE_HUE_SPREAD_DEGREES = 34f
