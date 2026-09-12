package dev.jtiisto.noise.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundCategory
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.playback.PlaybackSettings
import dev.jtiisto.noise.core.playback.PlaybackState
import dev.jtiisto.noise.core.playback.TimerState
import dev.jtiisto.noise.ui.components.SectionHeader
import dev.jtiisto.noise.ui.components.auroraBackground
import dev.jtiisto.noise.ui.components.rememberMixPalette
import dev.jtiisto.noise.ui.home.CatalogSection
import dev.jtiisto.noise.ui.home.HomeScreen
import dev.jtiisto.noise.ui.home.HomeViewModel
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSpacing
import dev.jtiisto.noise.ui.theme.HushTheme
import dev.jtiisto.noise.ui.theme.PillShape
import dev.jtiisto.noise.ui.theme.SheetShape

/**
 * The states every preview and screenshot reference is rendered from. Keeping
 * them in one object means a design change shows up in every reference at once
 * and nothing drifts.
 */
object PreviewStates {

    /** Nothing chosen yet — the first-run screen. */
    val idle = PlaybackState()

    /** A full three-layer mix, playing, 27:31 left on a 45-minute timer. */
    val playingTrio = PlaybackState(
        mix = Mix.of(
            SoundId.RAIN to 0.72f,
            SoundId.THUNDERSTORM to 0.54f,
            SoundId.BROWN to 0.38f,
        ),
        isPlaying = true,
        masterVolume = 0.8f,
        timer = TimerState(
            totalMillis = 45 * 60_000L,
            endAtEpochMillis = 0L,
            remainingMillis = 27 * 60_000L + 31_000L,
            fadeMillis = 45_000L,
        ),
        settings = PlaybackSettings(fadeOutSeconds = 45, lastTimerMinutes = 45),
    )

    /** Playing with no sleep timer — the indefinite mode. */
    val playingUntilCancelled = PlaybackState(
        mix = Mix.of(SoundId.CAMPFIRE to 0.7f, SoundId.WIND to 0.3f),
        isPlaying = true,
        masterVolume = 0.7f,
        timer = null,
        settings = PlaybackSettings(lastTimerMinutes = PlaybackSettings.TIMER_UNTIL_CANCELLED),
    )

    /** One layer, paused. */
    val pausedSingle = PlaybackState(
        mix = Mix.of(SoundId.OCEAN to 0.82f),
        isPlaying = false,
        masterVolume = 0.62f,
    )
}

/**
 * Renders the real [HomeScreen] against a [FakePlaybackController] seeded with
 * [state]. Previews stay interactive: tapping a tile really changes the mix.
 */
@Composable
fun HomeScreenPreview(state: PlaybackState) {
    val controller = remember(state) { FakePlaybackController(state) }
    val viewModel = remember(controller) { HomeViewModel(controller) }
    val live by controller.state.collectAsState()
    HushTheme {
        HomeScreen(state = live, ui = viewModel.uiState, actions = viewModel)
    }
}

/** One catalog section on the night ground, for narrow-width fit checks. */
@Composable
fun CatalogSectionPreview(category: SoundCategory, mix: Mix) {
    val controller = remember(mix) { FakePlaybackController(PlaybackState(mix = mix)) }
    val viewModel = remember(controller) { HomeViewModel(controller) }
    val live by controller.state.collectAsState()
    HushTheme {
        Box(
            Modifier
                .fillMaxSize()
                .auroraBackground(rememberMixPalette(live.mix))
                .padding(HushSpacing.screen),
        ) {
            Column {
                SectionHeader(category.title)
                Spacer(Modifier.height(HushSpacing.md))
                CatalogSection(sounds = SoundId.inCategory(category), mix = live.mix, actions = viewModel)
            }
        }
    }
}

/**
 * A stand-in for a `ModalBottomSheet`, which renders into its own window and
 * so never appears in a Compose Preview screenshot. Same scrim, same ground,
 * same corners and handle — what you see here is what the sheet looks like.
 */
@Composable
fun SheetPreviewFrame(
    mix: Mix,
    content: @Composable ColumnScope.() -> Unit,
) {
    HushTheme {
        val palette = rememberMixPalette(mix)
        Box(
            Modifier
                .fillMaxSize()
                .auroraBackground(palette)
                .background(HushColor.Scrim),
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(SheetShape)
                    .background(HushColor.NightSheet)
                    .padding(horizontal = HushSpacing.screen)
                    .padding(top = 12.dp, bottom = HushSpacing.xl),
            ) {
                Spacer(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(32.dp)
                        .height(4.dp)
                        .clip(PillShape)
                        .background(HushColor.HairlineStrong),
                )
                Spacer(Modifier.height(HushSpacing.lg))
                content()
            }
        }
    }
}
