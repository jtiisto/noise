package dev.jtiisto.noise

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.ui.components.auroraBackground
import dev.jtiisto.noise.ui.components.rememberMixPalette
import dev.jtiisto.noise.ui.critter.CritterKind
import dev.jtiisto.noise.ui.critterscenes.CritterScene
import dev.jtiisto.noise.ui.theme.CardShape
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushTheme

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
            CritterScene(kind = kind, palette = palette, variant = variant, size = 276.dp)
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
            CritterScene(kind = kind, palette = palette, variant = variant, size = tile)
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
