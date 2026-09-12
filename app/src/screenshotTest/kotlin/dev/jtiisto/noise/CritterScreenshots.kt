package dev.jtiisto.noise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.playback.PlaybackState
import dev.jtiisto.noise.ui.components.auroraBackground
import dev.jtiisto.noise.ui.components.rememberMixPalette
import dev.jtiisto.noise.ui.critter.Critter
import dev.jtiisto.noise.ui.critter.CritterKind
import dev.jtiisto.noise.ui.preview.HomeScreenPreview
import dev.jtiisto.noise.ui.theme.CardShape
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushTheme

/**
 * Reference renders that prove each critter is cute in isolation and sits
 * cleanly at the base of the orb in context. The three home states here cover
 * the animals the existing `HomeScreenshots` states do not (bird, duck,
 * firefly); the others (cat, frog, whale, fox) already appear in those.
 */
private const val CRITTER_NIGHT = 0xFF0B0F1AL

/** Wind → a round little bird. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = CRITTER_NIGHT)
@Composable
fun HomeWindBird() {
    HomeScreenPreview(PlaybackState(mix = Mix.of(SoundId.WIND to 0.6f), isPlaying = true, masterVolume = 0.7f))
}

/** Stream → a cheerful duck. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = CRITTER_NIGHT)
@Composable
fun HomeStreamDuck() {
    HomeScreenPreview(PlaybackState(mix = Mix.of(SoundId.STREAM to 0.55f), isPlaying = true, masterVolume = 0.7f))
}

/** Crickets → a glowing firefly. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = CRITTER_NIGHT)
@Composable
fun HomeCricketsFirefly() {
    HomeScreenPreview(PlaybackState(mix = Mix.of(SoundId.CRICKETS to 0.6f), isPlaying = true, masterVolume = 0.7f))
}

/** All seven critters, each on the ground tint of the sound it belongs to. */
@PreviewTest
@Preview(widthDp = 360, heightDp = 560, showBackground = true, backgroundColor = CRITTER_NIGHT)
@Composable
fun CritterGallery() {
    HushTheme {
        Box(Modifier.fillMaxSize().auroraBackground(rememberMixPalette(Mix.EMPTY))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CritterCell(CritterKind.CAT, Mix.EMPTY, "Cat")
                    CritterCell(CritterKind.FROG, Mix.of(SoundId.RAIN to 1f), "Frog")
                    CritterCell(CritterKind.WHALE, Mix.of(SoundId.OCEAN to 1f), "Whale")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CritterCell(CritterKind.BIRD, Mix.of(SoundId.WIND to 1f), "Bird")
                    CritterCell(CritterKind.FOX, Mix.of(SoundId.CAMPFIRE to 1f), "Fox")
                    CritterCell(CritterKind.DUCK, Mix.of(SoundId.STREAM to 1f), "Duck")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CritterCell(CritterKind.FIREFLY, Mix.of(SoundId.CRICKETS to 1f), "Firefly")
                }
            }
        }
    }
}

@Composable
private fun CritterCell(kind: CritterKind, mix: Mix, label: String) {
    val palette = rememberMixPalette(mix)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(100.dp)
                .clip(CardShape)
                .auroraBackground(palette),
            contentAlignment = Alignment.Center,
        ) {
            Critter(kind = kind, isPlaying = true, palette = palette, size = 84.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = HushColor.TextSecondary,
            textAlign = TextAlign.Center,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}
