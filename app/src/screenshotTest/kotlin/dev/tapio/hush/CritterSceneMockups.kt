package dev.tapio.hush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import dev.tapio.hush.ui.components.auroraBackground
import dev.tapio.hush.ui.components.rememberMixPalette
import dev.tapio.hush.ui.critter.CritterKind
import dev.tapio.hush.ui.critterscenes.CritterScene
import dev.tapio.hush.ui.critterscenes.CritterSceneFrame
import dev.tapio.hush.ui.theme.CardShape
import dev.tapio.hush.ui.theme.HushColor
import dev.tapio.hush.ui.theme.HushTheme

/**
 * DESIGN EXPLORATION mockups (static) for the "critter scenes" idea — a bolder,
 * storybook-sticker take on the home critter where each sound becomes a small
 * illustrated scene. Nothing here is wired into the shipped app; these renders
 * exist only for the owner to react to. See `docs/critter-scenes.md`.
 */
private const val SCENE_NIGHT = 0xFF0B0F1AL

/** The mix whose accent tints each scene, chosen to match its sound. */
private fun mixFor(kind: CritterKind): Mix = when (kind) {
    CritterKind.CAT -> Mix.EMPTY
    CritterKind.FROG -> Mix.of(SoundId.THUNDERSTORM to 1f)
    CritterKind.WHALE -> Mix.of(SoundId.OCEAN to 1f)
    CritterKind.FOX -> Mix.of(SoundId.CAMPFIRE to 1f)
    CritterKind.BIRD -> Mix.of(SoundId.WIND to 1f)
    CritterKind.FIREFLY -> Mix.of(SoundId.CRICKETS to 1f)
    CritterKind.DUCK -> Mix.of(SoundId.STREAM to 1f)
}

/** All seven scenes together, so the set can be judged as one cohesive family. */
@PreviewTest
@Preview(widthDp = 380, heightDp = 900, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneGallery() {
    HushTheme {
        Box(Modifier.fillMaxSize().auroraBackground(rememberMixPalette(Mix.EMPTY))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SceneCell(CritterKind.CAT, "Default · cat nook", 168.dp)
                    SceneCell(CritterKind.FROG, "Rain · frog", 168.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SceneCell(CritterKind.DUCK, "Stream · duck", 168.dp)
                    SceneCell(CritterKind.WHALE, "Ocean · whale", 168.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SceneCell(CritterKind.FOX, "Campfire · fox", 168.dp)
                    SceneCell(CritterKind.BIRD, "Wind · bird", 168.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SceneCell(CritterKind.FIREFLY, "Crickets · firefly", 168.dp)
                }
            }
        }
    }
}

/** Alternative compositions offered for two scenes, beside their primary. */
@PreviewTest
@Preview(widthDp = 380, heightDp = 560, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneAlternates() {
    HushTheme {
        Box(Modifier.fillMaxSize().auroraBackground(rememberMixPalette(Mix.EMPTY))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SceneCell(CritterKind.FROG, "Frog · umbrella", 168.dp, variant = 0)
                    SceneCell(CritterKind.FROG, "Frog · leaf tent", 168.dp, variant = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SceneCell(CritterKind.DUCK, "Duck · fishing", 168.dp, variant = 0)
                    SceneCell(CritterKind.DUCK, "Duck · bottoms-up", 168.dp, variant = 1)
                }
            }
        }
    }
}

// --- Each scene on its own, large, so the detail is visible --------------------

@PreviewTest
@Preview(widthDp = 300, heightDp = 300, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneCatNook() = BigScene(CritterKind.CAT)

@PreviewTest
@Preview(widthDp = 300, heightDp = 300, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneRainFrog() = BigScene(CritterKind.FROG)

@PreviewTest
@Preview(widthDp = 300, heightDp = 300, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneStreamDuck() = BigScene(CritterKind.DUCK)

@PreviewTest
@Preview(widthDp = 300, heightDp = 300, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneOceanWhale() = BigScene(CritterKind.WHALE)

@PreviewTest
@Preview(widthDp = 300, heightDp = 300, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneCampfireFox() = BigScene(CritterKind.FOX)

@PreviewTest
@Preview(widthDp = 300, heightDp = 300, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneWindBird() = BigScene(CritterKind.BIRD)

@PreviewTest
@Preview(widthDp = 300, heightDp = 300, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneCricketsFirefly() = BigScene(CritterKind.FIREFLY)

/**
 * One scene composed near a mock play orb, at a plausible "bigger" size, so the
 * owner can judge how a larger scene would sit on the home screen. The orb here
 * is a stand-in — the real HomeScreen is untouched.
 */
@PreviewTest
@Preview(widthDp = 360, heightDp = 420, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneNearOrb() {
    val mix = Mix.of(SoundId.THUNDERSTORM to 0.7f, SoundId.RAIN to 0.5f)
    val palette = rememberMixPalette(mix)
    HushTheme {
        Box(
            Modifier.fillMaxSize().auroraBackground(palette),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.padding(top = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The 200 dp orb box, with the scene overlaid at its base.
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(168.dp)) {
                        val r = size.minDimension / 2f
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val accent = palette.value.accent
                        drawCircle(accent.copy(alpha = 0.10f), r, c)
                        drawCircle(accent.copy(alpha = 0.14f), r * 0.82f, c)
                        drawCircle(accent.copy(alpha = 0.20f), r * 0.62f, c)
                        drawCircle(HushColor.Foreground.copy(alpha = 0.88f), r * 0.10f, Offset(c.x - r * 0.06f, c.y))
                        drawCircle(HushColor.Foreground.copy(alpha = 0.88f), r * 0.10f, Offset(c.x + r * 0.10f, c.y))
                    }
                    CritterScene(
                        kind = CritterKind.FROG,
                        isPlaying = true,
                        palette = palette,
                        variant = 0,
                        size = 150.dp,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Thunderstorm + Rain",
                    color = HushColor.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Scene ~150 dp at the base of a 200 dp orb box",
                    color = HushColor.TextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun BigScene(kind: CritterKind, variant: Int = 0) {
    val palette = rememberMixPalette(mixFor(kind))
    HushTheme {
        Box(
            Modifier.fillMaxSize().auroraBackground(palette),
            contentAlignment = Alignment.Center,
        ) {
            CritterScene(kind = kind, isPlaying = true, palette = palette, variant = variant, size = 276.dp)
        }
    }
}

@Composable
private fun SceneCell(kind: CritterKind, label: String, tile: androidx.compose.ui.unit.Dp, variant: Int = 0) {
    val palette = rememberMixPalette(mixFor(kind))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(tile).clip(CardShape).auroraBackground(palette),
            contentAlignment = Alignment.Center,
        ) {
            CritterScene(kind = kind, isPlaying = true, palette = palette, variant = variant, size = tile)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = HushColor.TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}


/**
 * PLACEMENT STUDY (owner direction 2026-09-13): chosen variants — frog umbrella,
 * duck bottoms-up — moved RIGHT OF CENTRE at the orb-base height, allowed to
 * overlap the play/pause glyph. In the real build the scene is decorative (no
 * pointer input), so the whole orb stays clickable under the overlap.
 */
@PreviewTest
@Preview(widthDp = 411, heightDp = 900, showBackground = true, backgroundColor = 0xFF0B0F1AL)
@Composable
fun ScenePlacementRightOfCentre() {
    HushTheme {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrbPlacementCell(
                label = "Thunderstorm + Rain",
                mix = Mix.of(SoundId.THUNDERSTORM to 0.7f, SoundId.RAIN to 0.5f),
                kind = CritterKind.FROG,
                variant = 0,
                paused = false,
            )
            OrbPlacementCell(
                label = "Stream + Crickets",
                mix = Mix.of(SoundId.STREAM to 0.6f, SoundId.CRICKETS to 0.5f),
                kind = CritterKind.DUCK,
                variant = 1,
                paused = true,
            )
            OrbPlacementCell(
                label = "Nothing playing",
                mix = Mix.EMPTY,
                kind = CritterKind.CAT,
                variant = 0,
                paused = true,
            )
        }
    }
}

/**
 * A mock of the home orb area: the 200 dp orb rings + a play/pause glyph, with
 * the scene overlaid right-of-centre at the base. Mirrors the real layout so the
 * overlap can be judged; the real HomeScreen is NOT touched by this study.
 */
@Composable
private fun OrbPlacementCell(
    label: String,
    mix: Mix,
    kind: CritterKind,
    variant: Int,
    paused: Boolean,
) {
    val palette = rememberMixPalette(mix)
    Box(
        Modifier.fillMaxWidth().height(268.dp).auroraBackground(palette),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp)) {
                val accent = palette.value.accent
                val c = Offset(size.width / 2f, size.height / 2f)
                val ring = size.minDimension / 2f
                drawCircle(accent.copy(alpha = 0.08f), ring, c)
                drawCircle(accent.copy(alpha = 0.12f), ring * 0.86f, c)
                // solid orb face
                drawCircle(accent.copy(alpha = 0.30f), ring * 0.62f, c)
                drawCircle(accent.copy(alpha = 0.55f), ring * 0.60f, c)
                // play triangle or pause bars
                val g = HushColor.Foreground.copy(alpha = 0.92f)
                if (paused) {
                    val bw = ring * 0.06f; val bh = ring * 0.28f
                    drawRoundRect(g, topLeft = Offset(c.x - bw * 2.4f, c.y - bh), size = androidx.compose.ui.geometry.Size(bw * 1.6f, bh * 2), cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw)) 
                    drawRoundRect(g, topLeft = Offset(c.x + bw * 0.8f, c.y - bh), size = androidx.compose.ui.geometry.Size(bw * 1.6f, bh * 2), cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw))
                } else {
                    val t = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x - ring * 0.16f, c.y - ring * 0.22f)
                        lineTo(c.x - ring * 0.16f, c.y + ring * 0.22f)
                        lineTo(c.x + ring * 0.24f, c.y)
                        close()
                    }
                    drawPath(t, g)
                }
            }
            // The scene: right of centre, at the base height, overlapping the orb.
            CritterScene(
                kind = kind,
                isPlaying = !paused,
                palette = palette,
                variant = variant,
                size = 138.dp,
                modifier = Modifier.align(Alignment.BottomCenter).offset(x = 52.dp, y = 18.dp),
            )
        }
        Text(
            text = label,
            color = HushColor.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

/**
 * Key frames of the scene motion, pinned so a still render shows what only flashes
 * past in the live scene: a frog eyes-open vs mid-blink, the cat's "z" trail at two
 * drift positions, and the duck's ripple ring small vs large. [CritterSceneFrame]
 * freezes the breathe/clock values the live [CritterScene] animates.
 */
@PreviewTest
@Preview(widthDp = 380, heightDp = 560, showBackground = true, backgroundColor = SCENE_NIGHT)
@Composable
fun SceneMotionFrames() {
    HushTheme {
        Box(Modifier.fillMaxSize().auroraBackground(rememberMixPalette(Mix.EMPTY))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PinnedSceneCell(CritterKind.FROG, "Frog · eyes open", clock = 0.0f)
                    PinnedSceneCell(CritterKind.FROG, "Frog · blink", clock = 0.55f)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PinnedSceneCell(CritterKind.CAT, "Cat z · early", clock = 0.1f)
                    PinnedSceneCell(CritterKind.CAT, "Cat z · late", clock = 0.6f)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PinnedSceneCell(CritterKind.DUCK, "Duck ripple · small", clock = 0.02f)
                    PinnedSceneCell(CritterKind.DUCK, "Duck ripple · large", clock = 0.75f)
                }
            }
        }
    }
}

@Composable
private fun PinnedSceneCell(kind: CritterKind, label: String, clock: Float) {
    val palette = rememberMixPalette(mixFor(kind))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(168.dp).clip(CardShape).auroraBackground(palette),
            contentAlignment = Alignment.Center,
        ) {
            CritterSceneFrame(
                kind = kind,
                breathe = 0.6f,
                clock = clock,
                isPlaying = true,
                palette = palette,
                size = 168.dp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = HushColor.TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
