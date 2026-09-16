package dev.tapio.hush

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import dev.tapio.hush.core.model.SoundCategory
import dev.tapio.hush.core.playback.PlaybackSettings
import dev.tapio.hush.ui.home.SoundDetailSheetContent
import dev.tapio.hush.ui.preview.HomeScreenPreview
import dev.tapio.hush.ui.preview.PreviewStates
import dev.tapio.hush.ui.preview.CatalogSectionPreview
import dev.tapio.hush.ui.preview.SheetPreviewFrame
import dev.tapio.hush.ui.settings.SettingsSheetContent
import dev.tapio.hush.ui.theme.mixPalette
import dev.tapio.hush.ui.timer.TimerSheetContent

/**
 * Reference renders of every screen state in `specs/ui.md`.
 *
 * 360 × 780 is the common modern phone in dp; the 320 × 640 pair proves the
 * three-column catalog and the pinned volume bar still fit the smallest
 * screen the app supports. The wide set (600 dp and up) covers the two-pane
 * layout of `specs/ui.md`, **Layout**.
 */
private const val PHONE_WIDTH = 360
private const val PHONE_HEIGHT = 780
private const val SMALL_WIDTH = 320
private const val SMALL_HEIGHT = 640
private const val NIGHT = 0xFF0B0F1AL

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomeIdleEmptyMix() {
    HomeScreenPreview(PreviewStates.idle)
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomePlayingThreeLayers() {
    HomeScreenPreview(PreviewStates.playingTrio)
}

/** Playing with nothing set to stop it: "No timer" pill, "until cancelled" status. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomePlayingUntilCancelled() {
    HomeScreenPreview(PreviewStates.playingUntilCancelled)
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomePausedSingleLayer() {
    HomeScreenPreview(PreviewStates.pausedSingle)
}

/** The morning after: the one-time crash notice sits above the orb. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomeCrashNotice() {
    HomeScreenPreview(PreviewStates.pausedSingle, crashReport = PreviewStates.CRASH_REPORT)
}

@PreviewTest
@Preview(widthDp = SMALL_WIDTH, heightDp = SMALL_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomeSmallPhone() {
    HomeScreenPreview(PreviewStates.playingTrio)
}

/** A 10" tablet in landscape: the playback pane at its 440 dp cap, six catalog columns. */
@PreviewTest
@Preview(widthDp = 1280, heightDp = 800, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomeTabletLandscape() {
    HomeScreenPreview(PreviewStates.playingTrio)
}

/** The same tablet upright: still two panes, three columns, "No timer" pill. */
@PreviewTest
@Preview(widthDp = 800, heightDp = 1280, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomeTabletPortrait() {
    HomeScreenPreview(PreviewStates.playingUntilCancelled)
}

/** A phone on its side is wide too; both panes scroll in the short window. */
@PreviewTest
@Preview(widthDp = 780, heightDp = 360, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomePhoneLandscape() {
    HomeScreenPreview(PreviewStates.playingTrio)
}

/** The narrowest window that splits at all: a 280 dp playback pane and a phone-width catalog. */
@PreviewTest
@Preview(widthDp = 600, heightDp = 960, showBackground = true, backgroundColor = NIGHT)
@Composable
fun HomeWideSmallest() {
    HomeScreenPreview(PreviewStates.idle)
}

/**
 * The widest labels in the catalog ("Thunderstorm", "Airplane cabin") at the
 * narrowest supported width: three tiles per row, nothing clipped.
 */
@PreviewTest
@Preview(widthDp = SMALL_WIDTH, heightDp = 300, showBackground = true, backgroundColor = NIGHT)
@Composable
fun CatalogNatureSmallPhone() {
    CatalogSectionPreview(
        category = SoundCategory.NATURE,
        mix = Mix.of(SoundId.THUNDERSTORM to 0.8f, SoundId.CRICKETS to 0.3f),
    )
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun TimerSheetRunning() {
    val state = PreviewStates.playingTrio
    SheetPreviewFrame(state.mix) {
        TimerSheetContent(
            isRunning = true,
            isPlaying = true,
            remainingMillis = state.timer?.remainingMillis,
            settings = state.settings,
            accent = mixPalette(state.mix).accent,
            onStart = {},
            onPlayUntilCancelled = {},
            onCancel = {},
            onFadeSecondsChange = {},
        )
    }
}

/**
 * The indefinite mode: "Until cancelled" heads the list, the fade control is
 * gone (nothing fades when nothing stops) and the primary button offers to
 * start playing rather than to start a timer.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun TimerSheetUntilCancelled() {
    val mix = PreviewStates.pausedSingle.mix
    SheetPreviewFrame(mix) {
        TimerSheetContent(
            isRunning = false,
            isPlaying = false,
            remainingMillis = null,
            settings = PlaybackSettings(lastTimerMinutes = PlaybackSettings.TIMER_UNTIL_CANCELLED),
            accent = mixPalette(mix).accent,
            onStart = {},
            onPlayUntilCancelled = {},
            onCancel = {},
            onFadeSecondsChange = {},
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun TimerSheetCustomLength() {
    val mix = PreviewStates.pausedSingle.mix
    SheetPreviewFrame(mix) {
        TimerSheetContent(
            isRunning = false,
            isPlaying = false,
            remainingMillis = null,
            settings = PlaybackSettings(fadeOutSeconds = 30, lastTimerMinutes = 200),
            accent = mixPalette(mix).accent,
            onStart = {},
            onPlayUntilCancelled = {},
            onCancel = {},
            onFadeSecondsChange = {},
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun SettingsSheet() {
    val state = PreviewStates.playingTrio
    SheetPreviewFrame(state.mix) {
        SettingsSheetContent(
            settings = state.settings.copy(mixWithOtherApps = true),
            accent = mixPalette(state.mix).accent,
            versionName = "0.1.0",
            onMixWithOtherAppsChange = {},
            onFadeSecondsChange = {},
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun SoundDetailSheetInMix() {
    val mix = Mix.of(SoundId.CAMPFIRE to 0.66f)
    SheetPreviewFrame(mix) {
        SoundDetailSheetContent(
            id = SoundId.CAMPFIRE,
            gain = 0.66f,
            mixIsFull = false,
            onGainChange = {},
            onAdd = {},
            onRemove = {},
        )
    }
}

@PreviewTest
@Preview(widthDp = SMALL_WIDTH, heightDp = SMALL_HEIGHT, showBackground = true, backgroundColor = NIGHT)
@Composable
fun SoundDetailSheetNotInMix() {
    val mix = PreviewStates.playingTrio.mix
    SheetPreviewFrame(mix) {
        SoundDetailSheetContent(
            id = SoundId.CRICKETS,
            gain = null,
            mixIsFull = true,
            onGainChange = {},
            onAdd = {},
            onRemove = {},
        )
    }
}
