package dev.jtiisto.noise.ui.critterscenes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.noise.ui.critter.CritterKind
import dev.jtiisto.noise.ui.theme.MixPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DESIGN EXPLORATION — not wired into the live app.
 *
 * A bolder, "storybook sticker" take on the home critter: instead of a tiny
 * ~54 dp figure at the base of the orb, each sound gets a small illustrated
 * *scene* — the same established character (see [CritterKind]) doing something
 * in a little world that matches its sound.
 *
 * These are **static** mockups for the owner to react to: there is no
 * animation here (no `rememberInfiniteTransition`, no motion) and nothing in
 * this package is referenced by the shipped UI. The scene is dispatched on the
 * existing [CritterKind] so it maps 1:1 onto the sounds the live critter
 * already covers.
 *
 * Everything is drawn as Compose vector art (Canvas primitives) so it renders
 * in the JVM screenshot harness — no emoji, no bitmaps. All colours, helpers
 * and per-scene drawing live *inside* the composable's draw lambda on purpose:
 * `@Composable` code is excluded from the coverage gate, so this draft art
 * needs no unit tests and does not dent Kover.
 *
 * @param variant picks between alternative compositions where a scene has more
 *   than one (0 = the primary). Frog and Duck each offer a variant so the owner
 *   can compare; other kinds ignore it.
 * @param palette read for the sound's accent hue, which tints each scene's glow
 *   and a few props so it stays coherent with the night ground behind it.
 */
@Composable
fun CritterScene(
    kind: CritterKind,
    palette: State<MixPalette>,
    modifier: Modifier = Modifier,
    variant: Int = 0,
    size: Dp = 220.dp,
) {
    val accent = palette.value.accent
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val tau = (2.0 * PI).toFloat()

        fun x(f: Float) = f * s
        fun y(f: Float) = f * s

        // --- Palette (matches the shipped Critter art, then extends it) ------
        val catBody = Color(0xFFB9B3DA)
        val catDark = Color(0xFF938CC0)
        val catEar = Color(0xFFE3A9C6)
        val frogBody = Color(0xFF83CE84)
        val frogDark = Color(0xFF5CAE64)
        val frogBelly = Color(0xFFD3ECAF)
        val whaleBody = Color(0xFF7DB0E8)
        val whaleDark = Color(0xFF5B8ED2)
        val whaleBelly = Color(0xFFDCEBFB)
        val foxBody = Color(0xFFEB9A57)
        val foxDark = Color(0xFFD07C3D)
        val foxCream = Color(0xFFF8E7D1)
        val duckBody = Color(0xFFF3D268)
        val duckDark = Color(0xFFE3BC4C)
        val duckBeak = Color(0xFFEE9A46)
        val birdBelly = Color(0xFFF4ECD4)
        val birdBeak = Color(0xFFF2B457)
        val fireflyBody = Color(0xFF423E57)
        val fireflyGlow = Color(0xFFEBF69C)
        val fireflyWing = Color(0xFFDCE4F2)
        val eyeColor = Color(0xFF2A2740)
        val shine = Color(0xFFFFFFFF)
        val blush = Color(0xFFF2A6B4)

        val moonlight = Color(0xFFF3EFD8)
        val starColor = Color(0xFFDCE4F6)
        val leafLight = Color(0xFF74C079)
        val leafDark = Color(0xFF4C9A57)
        val stemColor = Color(0xFF6E9A52)
        val rainColor = Color(0xFFCFE0F5)
        val waterLight = Color(0xFF6FA6DE)
        val waterMid = Color(0xFF4E86C4)
        val waterDark = Color(0xFF396EA6)
        val foam = Color(0xFFE6F1FC)
        val logColor = Color(0xFF7C5A40)
        val logEnd = Color(0xFFB08A63)
        val flameOuter = Color(0xFFF07A3C)
        val flameMid = Color(0xFFF6A83E)
        val flameCore = Color(0xFFFBE07A)
        val ember = Color(0xFFFBC46A)
        val grassLight = Color(0xFF5FA45C)
        val grassDark = Color(0xFF3E7A46)
        val petal = Color(0xFFE8A6D0)
        val petalCore = Color(0xFFF6E08A)
        val cushionColor = Color(0xFF7C6E9C)
        val cushionHi = Color(0xFF9A8CBC)
        val paneColor = Color(0xFF141E38)
        val frameColor = Color(0xFFCBBEDA)
        val fishBody = Color(0xFFEE9A6E)
        val fishBelly = Color(0xFFF7D3B6)

        // --- Shared primitives ------------------------------------------------
        fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) =
            drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2))

        val tp = Path()
        fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, color: Color) {
            tp.reset()
            tp.moveTo(ax, ay); tp.lineTo(bx, by); tp.lineTo(cx, cy); tp.close()
            drawPath(tp, color)
        }

        fun softGlow(cx: Float, cy: Float, rad: Float, color: Color, a0: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = a0),
                    0.5f to color.copy(alpha = a0 * 0.42f),
                    1f to color.copy(alpha = 0f),
                    center = Offset(cx, cy),
                    radius = rad,
                ),
                radius = rad,
                center = Offset(cx, cy),
            )
        }

        fun crescent(cx: Float, cy: Float, r: Float, color: Color) {
            val moon = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
            val cut = Path().apply {
                addOval(Rect(cx - r + r * 0.56f, cy - r - r * 0.30f, cx + r + r * 0.56f, cy + r - r * 0.30f))
            }
            drawPath(Path().apply { op(moon, cut, PathOperation.Difference) }, color)
        }

        fun sparkle(cx: Float, cy: Float, r: Float, color: Color) {
            val p = Path()
            val inner = r * 0.34f
            for (k in 0 until 8) {
                val ang = -PI.toFloat() / 2f + k * (PI.toFloat() / 4f)
                val rad = if (k % 2 == 0) r else inner
                val px = cx + rad * cos(ang)
                val py = cy + rad * sin(ang)
                if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            p.close()
            drawPath(p, color)
        }

        fun beadEye(cx: Float, cy: Float, r: Float) {
            drawCircle(eyeColor, r, Offset(cx, cy))
            drawCircle(shine, r * 0.36f, Offset(cx - r * 0.28f, cy - r * 0.34f))
        }

        fun closedEye(cx: Float, cy: Float, w: Float) {
            drawArc(
                color = eyeColor,
                startAngle = 22f, sweepAngle = 136f, useCenter = false,
                topLeft = Offset(cx - w / 2, cy - w / 2), size = Size(w, w),
                style = Stroke(width = w * 0.15f, cap = StrokeCap.Round),
            )
        }

        fun smile(cx: Float, cy: Float, w: Float, h: Float, color: Color, stroke: Float) {
            drawArc(
                color = color,
                startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        fun leaf(cx: Float, cy: Float, halfLen: Float, halfW: Float, rot: Float, fill: Color, vein: Color) {
            withTransform({ rotate(rot, pivot = Offset(cx, cy)) }) {
                val p = Path()
                p.moveTo(cx - halfLen, cy)
                p.quadraticTo(cx, cy - halfW, cx + halfLen, cy)
                p.quadraticTo(cx, cy + halfW, cx - halfLen, cy)
                p.close()
                drawPath(p, fill)
                drawLine(vein, Offset(cx - halfLen * 0.82f, cy), Offset(cx + halfLen * 0.9f, cy), s * 0.006f, StrokeCap.Round)
                for (j in 1..3) {
                    val t = cx - halfLen * 0.4f + j * halfLen * 0.32f
                    drawLine(vein, Offset(t, cy), Offset(t + halfLen * 0.12f, cy - halfW * 0.42f), s * 0.005f, StrokeCap.Round)
                    drawLine(vein, Offset(t, cy), Offset(t + halfLen * 0.12f, cy + halfW * 0.42f), s * 0.005f, StrokeCap.Round)
                }
            }
        }

        // --- Character bodies (matched to the shipped critters) --------------
        fun catCurled(cx: Float, base: Float, r: Float) {
            // Same proportions as the shipped curled cat, placed so [cx] is the
            // body centre and [base] the body's bottom edge.
            val cs = r * 2.2f
            val ox = cx - 0.52f * cs
            val oy = base - 0.96f * cs
            fun px(f: Float) = ox + f * cs
            fun py(f: Float) = oy + f * cs
            // Curled body.
            oval(px(0.52f), py(0.70f), 0.40f * cs, 0.26f * cs, catBody)
            // Tail sweeping across the front, tip resting up.
            drawArc(
                color = catDark,
                startAngle = 40f, sweepAngle = 200f, useCenter = false,
                topLeft = Offset(px(0.16f), py(0.52f)), size = Size(0.62f * cs, 0.42f * cs),
                style = Stroke(width = cs * 0.11f, cap = StrokeCap.Round),
            )
            // Head resting on the paws, front-left of the curl.
            val hx = px(0.35f); val hy = py(0.62f); val hr = 0.205f * cs
            drawCircle(catBody, hr, Offset(hx, hy))
            // Ears (outer body + pink inner) clearly on top of the head.
            tri(hx - hr * 0.86f, hy - hr * 0.62f, hx - hr * 0.30f, hy - hr * 1.28f, hx - hr * 0.02f, hy - hr * 0.74f, catBody)
            tri(hx + hr * 0.10f, hy - hr * 0.78f, hx + hr * 0.44f, hy - hr * 1.30f, hx + hr * 0.78f, hy - hr * 0.66f, catBody)
            tri(hx - hr * 0.62f, hy - hr * 0.66f, hx - hr * 0.30f, hy - hr * 1.02f, hx - hr * 0.14f, hy - hr * 0.70f, catEar)
            tri(hx + hr * 0.24f, hy - hr * 0.70f, hx + hr * 0.46f, hy - hr * 1.04f, hx + hr * 0.66f, hy - hr * 0.64f, catEar)
            // Sleeping eyes, a little nose and blush.
            closedEye(hx - hr * 0.36f, hy + hr * 0.02f, hr * 0.62f)
            closedEye(hx + hr * 0.42f, hy + hr * 0.02f, hr * 0.62f)
            tri(hx + hr * 0.00f, hy + hr * 0.30f, hx + hr * 0.14f, hy + hr * 0.30f, hx + hr * 0.07f, hy + hr * 0.44f, catEar)
            drawCircle(blush.copy(alpha = 0.55f), hr * 0.22f, Offset(hx - hr * 0.02f, hy + hr * 0.36f))
            // Curled front paw.
            oval(px(0.30f), py(0.86f), 0.16f * cs, 0.09f * cs, catDark)
        }

        fun foxCurled(cx: Float, base: Float, r: Float) {
            oval(cx + r * 0.10f, base - r * 0.30f, r * 1.02f, r * 0.60f, foxBody)
            drawArc(
                color = foxBody,
                startAngle = 28f, sweepAngle = 214f, useCenter = false,
                topLeft = Offset(cx - r * 1.0f, base - r * 0.72f),
                size = Size(r * 1.74f, r * 1.14f),
                style = Stroke(width = r * 0.36f, cap = StrokeCap.Round),
            )
            drawCircle(foxCream, r * 0.22f, Offset(cx - r * 0.78f, base + r * 0.06f))
            val hx = cx - r * 0.44f; val hy = base - r * 0.22f; val hr = r * 0.52f
            drawCircle(foxBody, hr, Offset(hx, hy))
            tri(hx - hr * 0.92f, hy - hr * 0.50f, hx - hr * 0.58f, hy - hr * 1.42f, hx - hr * 0.06f, hy - hr * 0.72f, foxBody)
            tri(hx - hr * 0.70f, hy - hr * 0.56f, hx - hr * 0.52f, hy - hr * 1.06f, hx - hr * 0.22f, hy - hr * 0.68f, foxDark)
            tri(hx + hr * 0.08f, hy - hr * 0.74f, hx + hr * 0.60f, hy - hr * 1.42f, hx + hr * 0.92f, hy - hr * 0.50f, foxBody)
            tri(hx + hr * 0.24f, hy - hr * 0.68f, hx + hr * 0.52f, hy - hr * 1.06f, hx + hr * 0.70f, hy - hr * 0.56f, foxDark)
            oval(hx, hy + hr * 0.34f, hr * 0.74f, hr * 0.58f, foxCream)
            closedEye(hx - hr * 0.40f, hy - hr * 0.02f, hr * 0.54f)
            closedEye(hx + hr * 0.40f, hy - hr * 0.02f, hr * 0.54f)
            drawCircle(eyeColor, hr * 0.13f, Offset(hx, hy + hr * 0.40f))
        }

        // --- The scenes -------------------------------------------------------

        /** DEFAULT — sleeping cat in a cosy nook: a moonlit window, a cushion, drifting z's. */
        fun sceneCatNook() {
            softGlow(x(0.5f), y(0.30f), s * 0.55f, accent, 0.16f)
            // Window.
            val wl = x(0.30f); val wr = x(0.72f); val wt = y(0.10f); val wb = y(0.48f)
            drawRoundRect(
                paneColor,
                topLeft = Offset(wl, wt), size = Size(wr - wl, wb - wt),
                cornerRadius = CornerRadius(s * 0.03f, s * 0.03f),
            )
            // Sky glow in the pane + moon + stars.
            softGlow((wl + wr) / 2f, y(0.24f), s * 0.24f, Color(0xFF3C4E86), 0.6f)
            crescent(x(0.60f), y(0.22f), s * 0.055f, moonlight)
            sparkle(x(0.40f), y(0.20f), s * 0.022f, starColor.copy(alpha = 0.9f))
            sparkle(x(0.45f), y(0.34f), s * 0.016f, starColor.copy(alpha = 0.7f))
            sparkle(x(0.66f), y(0.36f), s * 0.018f, starColor.copy(alpha = 0.8f))
            // Muntins + frame.
            drawLine(frameColor.copy(alpha = 0.55f), Offset((wl + wr) / 2f, wt), Offset((wl + wr) / 2f, wb), s * 0.012f)
            drawLine(frameColor.copy(alpha = 0.55f), Offset(wl, (wt + wb) / 2f), Offset(wr, (wt + wb) / 2f), s * 0.012f)
            drawRoundRect(
                frameColor,
                topLeft = Offset(wl, wt), size = Size(wr - wl, wb - wt),
                cornerRadius = CornerRadius(s * 0.03f, s * 0.03f),
                style = Stroke(width = s * 0.02f),
            )
            // Cushion.
            oval(x(0.5f), y(0.90f), x(0.38f), y(0.095f), cushionColor)
            oval(x(0.5f), y(0.875f), x(0.35f), y(0.07f), cushionHi)
            drawCircle(cushionColor, s * 0.016f, Offset(x(0.5f), y(0.87f)))
            // Cat.
            catCurled(x(0.5f), y(0.90f), s * 0.21f)
            // Drifting z's, accent-tinted.
            for (i in 0..2) {
                val t = i / 2f
                val zx = x(0.66f) + s * 0.10f * t
                val zy = y(0.62f) - s * 0.20f * t
                val zw = s * (0.05f + 0.03f * t)
                val a = 0.85f - 0.25f * t
                val c = accent.copy(alpha = a)
                val st = zw * 0.2f
                drawLine(c, Offset(zx, zy), Offset(zx + zw, zy), st, StrokeCap.Round)
                drawLine(c, Offset(zx + zw, zy), Offset(zx, zy + zw), st, StrokeCap.Round)
                drawLine(c, Offset(zx, zy + zw), Offset(zx + zw, zy + zw), st, StrokeCap.Round)
            }
        }

        /** RAIN / THUNDERSTORM — frog sheltering under a big leaf, rain streaks, a soft lightning glow. */
        fun sceneRainFrog() {
            // Soft lightning glow behind.
            softGlow(x(0.34f), y(0.26f), s * 0.5f, accent, 0.26f)
            softGlow(x(0.5f), y(0.5f), s * 0.6f, accent, 0.08f)
            // Rain streaks.
            for (i in 0 until 16) {
                val fx = ((i * 0.147f + 0.02f) % 1f)
                val fy = ((i * 0.31f) % 1f)
                val rx = x(fx)
                val ry = y(0.06f + fy * 0.8f)
                val len = s * (0.06f + (i % 3) * 0.02f)
                val a = 0.32f + (i % 4) * 0.06f
                drawLine(rainColor.copy(alpha = a), Offset(rx, ry), Offset(rx - s * 0.03f, ry + len), s * 0.008f, StrokeCap.Round)
            }
            // Puddle.
            oval(x(0.5f), y(0.955f), x(0.34f), y(0.028f), waterMid.copy(alpha = 0.5f))
            drawArc(
                color = rainColor.copy(alpha = 0.4f),
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(x(0.36f), y(0.93f)), size = Size(x(0.14f), y(0.05f)),
                style = Stroke(width = s * 0.006f, cap = StrokeCap.Round),
            )
            val frogCx = x(0.5f); val frogBase = y(0.9f); val fr = s * 0.2f
            if (variant == 1) {
                // ALT: a big leaf leaning over like a tent, the frog peeking out beneath it.
                leaf(x(0.5f), y(0.4f), s * 0.42f, s * 0.30f, -18f, leafLight, leafDark)
                leaf(x(0.5f), y(0.4f), s * 0.42f, s * 0.30f, -18f, Color(0x22FFFFFF), leafDark.copy(alpha = 0f))
            } else {
                // Umbrella leaf held aloft, stem down to the frog's hand.
                drawLine(stemColor, Offset(x(0.60f), y(0.28f)), Offset(x(0.66f), y(0.62f)), s * 0.018f, StrokeCap.Round)
                leaf(x(0.46f), y(0.24f), s * 0.40f, s * 0.24f, -12f, leafLight, leafDark)
                // A couple of drips rolling off the leaf tip.
                drawCircle(rainColor.copy(alpha = 0.7f), s * 0.012f, Offset(x(0.09f), y(0.34f)))
                drawCircle(rainColor.copy(alpha = 0.5f), s * 0.01f, Offset(x(0.86f), y(0.30f)))
            }
            // Frog.
            oval(frogCx - fr * 0.9f, frogBase, fr * 0.5f, fr * 0.28f, frogDark)
            oval(frogCx + fr * 0.9f, frogBase, fr * 0.5f, fr * 0.28f, frogDark)
            oval(frogCx, frogBase - fr * 0.5f, fr * 1.32f, fr * 1.06f, frogBody)
            oval(frogCx, frogBase - fr * 0.18f, fr * 0.78f, fr * 0.66f, frogBelly)
            val er = fr * 0.5f
            drawCircle(frogBody, er, Offset(frogCx - fr * 0.56f, frogBase - fr * 1.42f))
            drawCircle(frogBody, er, Offset(frogCx + fr * 0.56f, frogBase - fr * 1.42f))
            drawCircle(shine, er * 0.72f, Offset(frogCx - fr * 0.56f, frogBase - fr * 1.4f))
            drawCircle(shine, er * 0.72f, Offset(frogCx + fr * 0.56f, frogBase - fr * 1.4f))
            beadEye(frogCx - fr * 0.52f, frogBase - fr * 1.36f, er * 0.42f)
            beadEye(frogCx + fr * 0.6f, frogBase - fr * 1.36f, er * 0.42f)
            smile(frogCx, frogBase - fr * 0.7f, fr * 1.3f, fr * 0.95f, frogDark, s * 0.014f)
            drawCircle(blush.copy(alpha = 0.5f), fr * 0.2f, Offset(frogCx - fr * 0.95f, frogBase - fr * 0.55f))
            drawCircle(blush.copy(alpha = 0.5f), fr * 0.2f, Offset(frogCx + fr * 0.95f, frogBase - fr * 0.55f))
            if (variant != 1) {
                // Raised hand gripping the stem.
                oval(frogCx + fr * 0.8f, frogBase - fr * 1.1f, fr * 0.2f, fr * 0.16f, frogDark)
            }
        }

        /** OCEAN — the whale breaching among gentle waves, a fuller spout, a moonlit sky. */
        fun sceneOceanWhale() {
            softGlow(x(0.5f), y(0.34f), s * 0.55f, accent, 0.18f)
            crescent(x(0.78f), y(0.18f), s * 0.06f, moonlight)
            sparkle(x(0.2f), y(0.16f), s * 0.02f, starColor.copy(alpha = 0.85f))
            sparkle(x(0.32f), y(0.26f), s * 0.015f, starColor.copy(alpha = 0.7f))
            sparkle(x(0.64f), y(0.12f), s * 0.017f, starColor.copy(alpha = 0.8f))

            // Back wave.
            val backTop = y(0.60f)
            run {
                val p = Path()
                p.moveTo(0f, backTop)
                var xx = 0f
                var up = true
                while (xx < s) {
                    val nx = xx + s * 0.22f
                    p.quadraticTo(xx + s * 0.11f, backTop + (if (up) -s * 0.05f else s * 0.03f), nx.coerceAtMost(s), backTop)
                    xx = nx; up = !up
                }
                p.lineTo(s, s); p.lineTo(0f, s); p.close()
                drawPath(p, waterDark.copy(alpha = 0.9f))
            }

            // Whale breaching, tilted up out of the water.
            withTransform({ rotate(-24f, pivot = Offset(x(0.5f), y(0.62f))) }) {
                // Rounded tail flukes (a soft whale-tail V), behind the body.
                val fl = Path()
                fl.moveTo(x(0.66f), y(0.52f))
                fl.cubicTo(x(0.78f), y(0.40f), x(0.93f), y(0.40f), x(0.93f), y(0.49f))
                fl.cubicTo(x(0.88f), y(0.53f), x(0.80f), y(0.55f), x(0.72f), y(0.57f))
                fl.cubicTo(x(0.82f), y(0.59f), x(0.92f), y(0.64f), x(0.90f), y(0.72f))
                fl.cubicTo(x(0.82f), y(0.67f), x(0.74f), y(0.61f), x(0.66f), y(0.57f))
                fl.close()
                drawPath(fl, whaleDark)
                oval(x(0.5f), y(0.56f), s * 0.24f, s * 0.16f, whaleBody)
                oval(x(0.42f), y(0.62f), s * 0.16f, s * 0.09f, whaleBelly)
                // Flipper.
                oval(x(0.44f), y(0.68f), s * 0.06f, s * 0.035f, whaleDark)
                smile(x(0.34f), y(0.6f), s * 0.14f, s * 0.09f, whaleDark, s * 0.012f)
                beadEye(x(0.33f), y(0.53f), s * 0.028f)
            }
            // Spout — a fuller fountain from the blowhole.
            val bx = x(0.44f); val by = y(0.34f)
            drawLine(foam.copy(alpha = 0.8f), Offset(bx, by + s * 0.06f), Offset(bx, by - s * 0.02f), s * 0.02f, StrokeCap.Round)
            for (k in -2..2) {
                val ang = k * 0.28f
                val dx = sin(ang) * s * 0.13f
                val dy = -cos(ang) * s * 0.13f
                drawCircle(foam.copy(alpha = 0.85f - kotlin.math.abs(k) * 0.14f), s * (0.026f - kotlin.math.abs(k) * 0.003f), Offset(bx + dx, by + dy))
            }
            drawCircle(foam.copy(alpha = 0.6f), s * 0.02f, Offset(bx - s * 0.05f, by - s * 0.02f))
            drawCircle(foam.copy(alpha = 0.6f), s * 0.018f, Offset(bx + s * 0.05f, by - s * 0.03f))

            // Front wave (over the whale's lower body) + foam line + splash.
            val frontTop = y(0.72f)
            run {
                val p = Path()
                p.moveTo(0f, frontTop)
                var xx = 0f
                var up = true
                while (xx < s) {
                    val nx = xx + s * 0.26f
                    p.quadraticTo(xx + s * 0.13f, frontTop + (if (up) -s * 0.06f else s * 0.04f), nx.coerceAtMost(s), frontTop)
                    xx = nx; up = !up
                }
                p.lineTo(s, s); p.lineTo(0f, s); p.close()
                drawPath(p, waterMid)
            }
            drawArc(
                color = foam.copy(alpha = 0.7f),
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(x(0.34f), y(0.66f)), size = Size(x(0.32f), y(0.12f)),
                style = Stroke(width = s * 0.01f, cap = StrokeCap.Round),
            )
            for (k in 0 until 5) {
                val a = 0.5f + (k % 2) * 0.2f
                drawCircle(foam.copy(alpha = a), s * (0.012f + (k % 2) * 0.006f), Offset(x(0.34f + k * 0.08f), y(0.66f - (k % 2) * 0.04f)))
            }
            // A light highlight on the front wave.
            drawLine(waterLight.copy(alpha = 0.5f), Offset(x(0.1f), y(0.8f)), Offset(x(0.9f), y(0.8f)), s * 0.01f, StrokeCap.Round)
        }

        /** CAMPFIRE — the fox curled beside a small fire, a log, embers drifting up, a cosy glow. */
        fun sceneCampfireFox() {
            softGlow(x(0.36f), y(0.62f), s * 0.62f, Color(0xFFF7A54A), 0.34f)
            softGlow(x(0.36f), y(0.6f), s * 0.3f, accent, 0.12f)
            sparkle(x(0.8f), y(0.16f), s * 0.018f, starColor.copy(alpha = 0.7f))
            sparkle(x(0.68f), y(0.24f), s * 0.014f, starColor.copy(alpha = 0.6f))
            // Ground line.
            oval(x(0.5f), y(0.94f), x(0.5f), y(0.06f), Color(0xFF1A2138).copy(alpha = 0.7f))

            // Fox curled, to the right, leaning toward the fire.
            foxCurled(x(0.66f), y(0.82f), s * 0.2f)

            // Campfire, to the left.
            val fx = x(0.32f); val fbase = y(0.84f)
            // Crossed logs.
            withTransform({ rotate(18f, pivot = Offset(fx, fbase)) }) {
                drawRoundRect(logColor, topLeft = Offset(fx - s * 0.16f, fbase - s * 0.03f), size = Size(s * 0.32f, s * 0.055f), cornerRadius = CornerRadius(s * 0.03f, s * 0.03f))
                drawCircle(logEnd, s * 0.026f, Offset(fx + s * 0.15f, fbase))
            }
            withTransform({ rotate(-16f, pivot = Offset(fx, fbase)) }) {
                drawRoundRect(logColor, topLeft = Offset(fx - s * 0.16f, fbase - s * 0.03f), size = Size(s * 0.32f, s * 0.055f), cornerRadius = CornerRadius(s * 0.03f, s * 0.03f))
                drawCircle(logEnd, s * 0.026f, Offset(fx - s * 0.15f, fbase))
            }
            // Flames (layered teardrops).
            fun flame(cx: Float, tipY: Float, baseY: Float, halfW: Float, color: Color) {
                val p = Path()
                p.moveTo(cx, tipY)
                p.cubicTo(cx + halfW, tipY + (baseY - tipY) * 0.5f, cx + halfW, baseY, cx, baseY)
                p.cubicTo(cx - halfW, baseY, cx - halfW, tipY + (baseY - tipY) * 0.5f, cx, tipY)
                p.close()
                drawPath(p, color)
            }
            flame(fx, fbase - s * 0.28f, fbase - s * 0.02f, s * 0.11f, flameOuter)
            flame(fx + s * 0.02f, fbase - s * 0.2f, fbase - s * 0.02f, s * 0.075f, flameMid)
            flame(fx, fbase - s * 0.12f, fbase - s * 0.01f, s * 0.04f, flameCore)
            // Embers drifting up.
            for (k in 0 until 6) {
                val t = (k * 0.17f) % 1f
                val ex = fx + sin(k * 1.7f) * s * 0.1f
                val ey = fbase - s * 0.3f - t * s * 0.4f
                drawCircle(ember.copy(alpha = 0.9f - t * 0.7f), s * (0.012f - t * 0.006f), Offset(ex, ey))
            }
        }

        /** WIND — the bird on a swaying reed, leaves and gusts blowing past. */
        fun sceneWindBird() {
            softGlow(x(0.5f), y(0.4f), s * 0.55f, accent, 0.16f)
            // Gust lines.
            for (k in 0 until 3) {
                val gy = y(0.24f + k * 0.12f)
                drawArc(
                    color = Color.White.copy(alpha = 0.12f + k * 0.02f),
                    startAngle = 200f, sweepAngle = 130f, useCenter = false,
                    topLeft = Offset(x(0.05f + k * 0.06f), gy), size = Size(x(0.5f), y(0.1f)),
                    style = Stroke(width = s * 0.008f, cap = StrokeCap.Round),
                )
            }
            // Curved branch swaying to the right.
            val branch = Path()
            branch.moveTo(x(0.12f), y(1.0f))
            branch.cubicTo(x(0.26f), y(0.78f), x(0.44f), y(0.72f), x(0.74f), y(0.66f))
            drawPath(branch, stemColor, style = Stroke(width = s * 0.028f, cap = StrokeCap.Round))
            // A couple of leaves on the branch.
            leaf(x(0.6f), y(0.62f), s * 0.09f, s * 0.05f, -28f, leafLight, leafDark)
            leaf(x(0.7f), y(0.68f), s * 0.08f, s * 0.045f, -8f, leafLight, leafDark)

            // Bird perched, tinted with the wind accent.
            val body = lerp(accent, Color.White, 0.4f)
            val wing = lerp(accent, Color.Black, 0.12f)
            val bx = x(0.5f); val by = y(0.5f); val br = s * 0.16f
            // Feet gripping the branch.
            drawLine(birdBeak, Offset(bx - s * 0.04f, by + br * 0.9f), Offset(bx - s * 0.04f, y(0.68f)), s * 0.012f, StrokeCap.Round)
            drawLine(birdBeak, Offset(bx + s * 0.04f, by + br * 0.9f), Offset(bx + s * 0.04f, y(0.69f)), s * 0.012f, StrokeCap.Round)
            tri(bx - br * 1.5f, by - br * 0.1f, bx - br * 0.6f, by - br * 0.4f, bx - br * 0.7f, by + br * 0.5f, wing)
            drawCircle(body, br, Offset(bx, by))
            oval(bx + br * 0.1f, by + br * 0.5f, br * 0.62f, br * 0.55f, birdBelly)
            withTransform({ rotate(-16f, pivot = Offset(bx - br * 0.2f, by - br * 0.2f)) }) {
                drawArc(color = wing, startAngle = 150f, sweepAngle = 150f, useCenter = true, topLeft = Offset(bx - br * 0.5f, by - br * 0.4f), size = Size(br * 0.95f, br * 1.1f))
            }
            tri(bx - br * 0.1f, by - br * 0.9f, bx + br * 0.1f, by - br * 1.3f, bx + br * 0.28f, by - br * 0.85f, body)
            tri(bx + br * 0.85f, by - br * 0.15f, bx + br * 1.34f, by, bx + br * 0.85f, by + br * 0.18f, birdBeak)
            beadEye(bx + br * 0.36f, by - br * 0.18f, s * 0.02f)
            drawCircle(blush.copy(alpha = 0.5f), br * 0.2f, Offset(bx + br * 0.62f, by + br * 0.12f))

            // Leaves blowing past, with faint motion streaks.
            leaf(x(0.24f), y(0.34f), s * 0.06f, s * 0.032f, 24f, leafDark, leafLight.copy(alpha = 0f))
            drawArc(color = Color.White.copy(alpha = 0.12f), startAngle = 200f, sweepAngle = 120f, useCenter = false, topLeft = Offset(x(0.1f), y(0.32f)), size = Size(x(0.16f), y(0.06f)), style = Stroke(width = s * 0.006f, cap = StrokeCap.Round))
            leaf(x(0.86f), y(0.44f), s * 0.05f, s * 0.028f, -40f, leafDark, leafLight.copy(alpha = 0f))
            leaf(x(0.34f), y(0.86f), s * 0.05f, s * 0.026f, 12f, leafDark, leafLight.copy(alpha = 0f))
        }

        /** CRICKETS — the firefly among tall grass and a night blossom under a crescent moon. */
        fun sceneCricketsFirefly() {
            softGlow(x(0.5f), y(0.66f), s * 0.5f, accent, 0.2f)
            crescent(x(0.8f), y(0.16f), s * 0.055f, moonlight)
            sparkle(x(0.22f), y(0.14f), s * 0.02f, starColor.copy(alpha = 0.85f))
            sparkle(x(0.34f), y(0.24f), s * 0.014f, starColor.copy(alpha = 0.65f))
            sparkle(x(0.62f), y(0.2f), s * 0.016f, starColor.copy(alpha = 0.75f))

            // Tall grass blades from the bottom.
            fun blade(baseX: Float, height: Float, bend: Float, color: Color) {
                val p = Path()
                p.moveTo(baseX - s * 0.02f, y(1.0f))
                p.quadraticTo(baseX + bend * 0.5f, y(1.0f) - height * 0.6f, baseX + bend, y(1.0f) - height)
                p.quadraticTo(baseX + bend * 0.5f, y(1.0f) - height * 0.6f, baseX + s * 0.02f, y(1.0f))
                p.close()
                drawPath(p, color)
            }
            blade(x(0.12f), s * 0.4f, s * 0.06f, grassDark)
            blade(x(0.2f), s * 0.52f, -s * 0.05f, grassLight)
            blade(x(0.86f), s * 0.46f, -s * 0.07f, grassDark)
            blade(x(0.78f), s * 0.56f, s * 0.05f, grassLight)
            blade(x(0.5f), s * 0.3f, s * 0.04f, grassDark)

            // Night blossom on a stem.
            val blx = x(0.28f); val bly = y(0.6f)
            drawLine(grassDark, Offset(blx, y(1.0f)), Offset(blx, bly), s * 0.01f, StrokeCap.Round)
            for (k in 0 until 5) {
                val ang = -PI.toFloat() / 2f + k * (tau / 5f)
                oval(blx + cos(ang) * s * 0.05f, bly + sin(ang) * s * 0.05f, s * 0.035f, s * 0.028f, petal)
            }
            drawCircle(petalCore, s * 0.03f, Offset(blx, bly))
            softGlow(blx, bly, s * 0.08f, petalCore, 0.4f)

            // A couple of extra firefly glimmers.
            softGlow(x(0.66f), y(0.42f), s * 0.07f, fireflyGlow, 0.55f)
            drawCircle(fireflyGlow.copy(alpha = 0.8f), s * 0.012f, Offset(x(0.66f), y(0.42f)))
            softGlow(x(0.16f), y(0.5f), s * 0.055f, fireflyGlow, 0.45f)
            drawCircle(fireflyGlow.copy(alpha = 0.7f), s * 0.01f, Offset(x(0.16f), y(0.5f)))

            // The firefly, centre.
            val cx = x(0.52f); val cy = y(0.5f); val fr = s * 0.16f
            softGlow(cx, cy + fr * 1.4f, fr * 2.2f, fireflyGlow, 0.6f)
            oval(cx - fr * 0.7f, cy - fr * 0.3f, fr * 0.7f, fr * 0.44f, fireflyWing.copy(alpha = 0.5f))
            oval(cx + fr * 0.7f, cy - fr * 0.3f, fr * 0.7f, fr * 0.44f, fireflyWing.copy(alpha = 0.5f))
            drawLine(fireflyBody, Offset(cx - fr * 0.25f, cy - fr * 0.85f), Offset(cx - fr * 0.5f, cy - fr * 1.4f), s * 0.008f, StrokeCap.Round)
            drawLine(fireflyBody, Offset(cx + fr * 0.25f, cy - fr * 0.85f), Offset(cx + fr * 0.5f, cy - fr * 1.4f), s * 0.008f, StrokeCap.Round)
            drawCircle(fireflyGlow, s * 0.014f, Offset(cx - fr * 0.5f, cy - fr * 1.4f))
            drawCircle(fireflyGlow, s * 0.014f, Offset(cx + fr * 0.5f, cy - fr * 1.4f))
            drawCircle(fireflyBody, fr * 0.55f, Offset(cx, cy - fr * 0.6f))
            oval(cx, cy + fr * 0.25f, fr * 0.55f, fr * 0.7f, fireflyBody)
            beadEye(cx - fr * 0.2f, cy - fr * 0.65f, s * 0.017f)
            beadEye(cx + fr * 0.2f, cy - fr * 0.65f, s * 0.017f)
            drawCircle(fireflyGlow.copy(alpha = 0.95f), fr * 0.42f, Offset(cx, cy + fr * 0.7f))
        }

        /** STREAM — the duck fishing/dabbling at a little stream, ripples, reeds, a fish peeking. */
        fun sceneStreamDuck() {
            softGlow(x(0.5f), y(0.4f), s * 0.5f, accent, 0.16f)
            // Stream band.
            drawRoundRect(waterMid, topLeft = Offset(0f, y(0.66f)), size = Size(s, y(0.34f)), cornerRadius = CornerRadius(0f, 0f))
            oval(x(0.5f), y(0.66f), x(0.55f), y(0.05f), waterLight.copy(alpha = 0.6f))
            // Ripple highlights.
            for (k in 0 until 4) {
                drawArc(color = waterLight.copy(alpha = 0.55f), startAngle = 200f, sweepAngle = 140f, useCenter = false, topLeft = Offset(x(0.1f + k * 0.22f), y(0.78f + (k % 2) * 0.06f)), size = Size(x(0.14f), y(0.05f)), style = Stroke(width = s * 0.008f, cap = StrokeCap.Round))
            }
            // Bank on the left.
            oval(x(0.16f), y(0.72f), x(0.3f), y(0.12f), grassDark)
            oval(x(0.14f), y(0.68f), x(0.24f), y(0.08f), grassLight)

            // Reeds on the right.
            fun reed(baseX: Float, height: Float, cattail: Boolean) {
                drawLine(grassDark, Offset(baseX, y(0.7f)), Offset(baseX + s * 0.02f, y(0.7f) - height), s * 0.012f, StrokeCap.Round)
                if (cattail) {
                    oval(baseX + s * 0.02f, y(0.7f) - height, s * 0.02f, s * 0.05f, logColor)
                } else {
                    leaf(baseX + s * 0.03f, y(0.7f) - height * 0.9f, s * 0.06f, s * 0.02f, -50f, grassLight, grassDark)
                }
            }
            reed(x(0.84f), s * 0.42f, true)
            reed(x(0.9f), s * 0.34f, false)
            reed(x(0.8f), s * 0.3f, false)

            if (variant == 1) {
                // ALT: "bottoms-up" full dabble — tail straight up, head under the water.
                val dcx = x(0.46f)
                oval(dcx, y(0.64f), s * 0.16f, s * 0.13f, duckBody)          // rump above water
                tri(dcx - s * 0.04f, y(0.52f), dcx - s * 0.12f, y(0.4f), dcx + s * 0.02f, y(0.46f), duckDark) // upturned tail
                // Feet paddling in the air.
                drawLine(duckBeak, Offset(dcx + s * 0.05f, y(0.56f)), Offset(dcx + s * 0.12f, y(0.48f)), s * 0.014f, StrokeCap.Round)
                drawLine(duckBeak, Offset(dcx + s * 0.09f, y(0.58f)), Offset(dcx + s * 0.16f, y(0.52f)), s * 0.014f, StrokeCap.Round)
                oval(dcx + s * 0.08f, y(0.6f), s * 0.09f, s * 0.05f, duckDark) // wing
                // Ripple rings around the dunked head.
                for (k in 0 until 3) {
                    drawArc(color = foam.copy(alpha = 0.6f - k * 0.15f), startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = Offset(dcx - s * (0.08f + k * 0.05f), y(0.68f) - s * (0.02f + k * 0.012f)), size = Size(s * (0.16f + k * 0.1f), s * (0.04f + k * 0.024f)), style = Stroke(width = s * 0.006f))
                }
            } else {
                // Duck at the water's edge, head dipped toward a fish.
                val dcx = x(0.42f); val dbase = y(0.66f); val dr = s * 0.16f
                tri(dcx - dr * 1.1f, dbase - dr * 0.4f, dcx - dr * 1.7f, dbase - dr * 0.8f, dcx - dr * 1.1f, dbase - dr * 0.05f, duckDark) // tail
                oval(dcx, dbase - dr * 0.3f, dr * 1.15f, dr * 0.78f, duckBody)   // body
                drawArc(color = duckDark, startAngle = 20f, sweepAngle = 150f, useCenter = false, topLeft = Offset(dcx - dr * 0.5f, dbase - dr * 0.85f), size = Size(dr * 1.2f, dr * 1.0f), style = Stroke(width = s * 0.01f, cap = StrokeCap.Round)) // wing
                // Head dipping down toward the water.
                val hx = dcx + dr * 1.0f; val hy = dbase - dr * 0.1f; val hr = dr * 0.62f
                drawLine(duckBody, Offset(dcx + dr * 0.5f, dbase - dr * 0.7f), Offset(hx, hy), dr * 0.5f, StrokeCap.Round) // neck
                drawCircle(duckBody, hr, Offset(hx, hy))
                tri(hx + hr * 0.3f, hy + hr * 0.2f, hx + hr * 1.2f, hy + hr * 0.7f, hx + hr * 0.3f, hy + hr * 0.8f, duckBeak) // beak pointing down to water
                beadEye(hx + hr * 0.1f, hy - hr * 0.1f, s * 0.016f)
                drawCircle(blush.copy(alpha = 0.45f), hr * 0.28f, Offset(hx - hr * 0.4f, hy + hr * 0.2f))
                // Ripple rings where the beak meets the water.
                for (k in 0 until 3) {
                    drawArc(color = foam.copy(alpha = 0.6f - k * 0.15f), startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = Offset(hx + hr * 0.7f - s * (0.05f + k * 0.04f), y(0.71f) - s * (0.012f + k * 0.008f)), size = Size(s * (0.1f + k * 0.08f), s * (0.026f + k * 0.02f)), style = Stroke(width = s * 0.006f))
                }
                // A little fish peeking at the surface.
                val fx = hx + hr * 1.2f; val fy = y(0.76f)
                oval(fx, fy, s * 0.05f, s * 0.032f, fishBody)
                oval(fx - s * 0.01f, fy + s * 0.008f, s * 0.03f, s * 0.018f, fishBelly)
                tri(fx + s * 0.045f, fy, fx + s * 0.09f, fy - s * 0.03f, fx + s * 0.09f, fy + s * 0.03f, fishBody)
                drawCircle(eyeColor, s * 0.007f, Offset(fx - s * 0.02f, fy - s * 0.005f))
            }
        }

        when (kind) {
            CritterKind.CAT -> sceneCatNook()
            CritterKind.FROG -> sceneRainFrog()
            CritterKind.WHALE -> sceneOceanWhale()
            CritterKind.FOX -> sceneCampfireFox()
            CritterKind.BIRD -> sceneWindBird()
            CritterKind.FIREFLY -> sceneCricketsFirefly()
            CritterKind.DUCK -> sceneStreamDuck()
        }
    }
}
