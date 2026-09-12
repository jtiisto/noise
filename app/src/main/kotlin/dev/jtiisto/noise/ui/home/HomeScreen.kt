package dev.jtiisto.noise.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.noise.R
import dev.jtiisto.noise.core.model.SoundCategory
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.model.Scenes
import dev.jtiisto.noise.core.playback.PlaybackState
import dev.jtiisto.noise.core.playback.TimerState
import dev.jtiisto.noise.ui.components.HushIconButton
import dev.jtiisto.noise.ui.components.HushChip
import dev.jtiisto.noise.ui.components.HushSlider
import dev.jtiisto.noise.ui.components.SectionHeader
import dev.jtiisto.noise.ui.components.auroraBackground
import dev.jtiisto.noise.ui.components.rememberMixPalette
import dev.jtiisto.noise.ui.formatCountdown
import dev.jtiisto.noise.ui.formatPercent
import dev.jtiisto.noise.ui.remainingMinutes
import dev.jtiisto.noise.ui.settings.SettingsSheet
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSpacing
import dev.jtiisto.noise.ui.theme.MixPalette
import dev.jtiisto.noise.ui.theme.PillShape
import dev.jtiisto.noise.ui.theme.WordmarkStyle
import dev.jtiisto.noise.ui.theme.accentFor
import dev.jtiisto.noise.ui.timer.TimerSheet

/** Home wired to its ViewModel. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onPlaybackRequested: () -> Unit = {},
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    HomeScreen(
        state = playback,
        ui = viewModel.uiState,
        actions = viewModel,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        onPlaybackRequested = onPlaybackRequested,
    )
}

/**
 * The whole app, as a pure function of [state] plus the local [ui].
 *
 * One vertical scroll holds everything between the fixed header and the
 * pinned master-volume bar, so the orb scrolls away with the catalog rather
 * than the catalog scrolling inside a window of its own.
 *
 * [onPlaybackRequested] fires just before anything that will make sound; the
 * host uses it to ask for the notification permission exactly once.
 */
@Composable
fun HomeScreen(
    state: PlaybackState,
    ui: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onPlaybackRequested: () -> Unit = {},
) {
    val context = LocalContext.current
    val palette = rememberMixPalette(state.mix)
    val mix = state.mix
    val timer = state.timer

    val message = ui.message
    LaunchedEffect(message?.id) {
        if (message != null) {
            snackbarHostState.showSnackbar(context.getString(message.textRes))
            actions.onMessageShown(message.id)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .auroraBackground(palette),
    ) {
        Column(Modifier.fillMaxSize()) {
            HomeHeader(
                timer = timer,
                palette = palette,
                onTimerClick = actions::onTimerPillClick,
                onSettingsClick = actions::onSettingsClick,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )

            Box(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(HushSpacing.md))

                    PlayOrb(
                        isPlaying = state.isPlaying,
                        enabled = !mix.isEmpty,
                        palette = palette,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.cd_pause else R.string.cd_play,
                        ),
                        onClick = {
                            if (!state.isPlaying) onPlaybackRequested()
                            actions.onPlayPauseClick()
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )

                    // The orb's box already carries its halo's worth of margin.
                    Spacer(Modifier.height(HushSpacing.sm))
                    NowPlayingText(state = state)

                    Spacer(Modifier.height(HushSpacing.xl))
                    AnimatedVisibility(
                        visible = !mix.isEmpty,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            MixCard(
                                layers = mix.layers,
                                actions = actions,
                                modifier = Modifier.padding(horizontal = HushSpacing.screen),
                            )
                            Spacer(Modifier.height(HushSpacing.xl))
                        }
                    }

                    SectionHeader(
                        title = stringResource(R.string.scenes_header),
                        modifier = Modifier.padding(horizontal = HushSpacing.screen),
                    )
                    Spacer(Modifier.height(HushSpacing.md))
                    SceneRow(activeSceneId = Scenes.matching(mix)?.id, actions = actions)

                    Spacer(Modifier.height(HushSpacing.xl))
                    SoundCategory.entries.forEach { category ->
                        SectionHeader(
                            title = category.title,
                            modifier = Modifier.padding(horizontal = HushSpacing.screen),
                        )
                        Spacer(Modifier.height(HushSpacing.md))
                        CatalogSection(
                            sounds = SoundId.inCategory(category),
                            mix = mix,
                            actions = actions,
                            modifier = Modifier.padding(horizontal = HushSpacing.screen),
                        )
                        Spacer(Modifier.height(HushSpacing.xl))
                    }
                    Spacer(Modifier.height(HushSpacing.sm))
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = HushSpacing.md, vertical = HushSpacing.sm),
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = HushColor.NightSheet,
                        contentColor = HushColor.TextPrimary,
                        shape = PillShape,
                    )
                }
            }

            MasterVolumeBar(
                volume = state.masterVolume,
                palette = palette,
                onVolumeChange = actions::onMasterVolumeChange,
            )
        }
    }

    when (val sheet = ui.sheet) {
        HomeSheet.None -> Unit
        HomeSheet.Timer -> TimerSheet(
            isRunning = timer != null,
            remainingMillis = timer?.remainingMillis,
            settings = state.settings,
            accent = palette.value.accent,
            onStart = { minutes ->
                onPlaybackRequested()
                actions.onStartTimer(minutes)
            },
            onCancel = actions::onCancelTimer,
            onFadeSecondsChange = actions::onFadeSecondsChange,
            onDismiss = actions::onSheetDismiss,
        )
        HomeSheet.Settings -> SettingsSheet(
            settings = state.settings,
            accent = palette.value.accent,
            onMixWithOtherAppsChange = actions::onMixWithOtherAppsChange,
            onFadeSecondsChange = actions::onFadeSecondsChange,
            onDismiss = actions::onSheetDismiss,
        )
        is HomeSheet.Sound -> SoundDetailSheet(
            id = sheet.id,
            gain = mix.layer(sheet.id)?.gain,
            mixIsFull = mix.isFull,
            actions = actions,
            onDismiss = actions::onSheetDismiss,
        )
    }
}

@Composable
private fun HomeHeader(
    timer: TimerState?,
    palette: State<MixPalette>,
    onTimerClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = HushSpacing.screen, end = HushSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.wordmark).uppercase(),
            style = WordmarkStyle,
            color = HushColor.TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        TimerPill(timer = timer, accent = palette.value.accent, onClick = onTimerClick)
        Spacer(Modifier.width(HushSpacing.xs))
        HushIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.cd_settings),
            onClick = onSettingsClick,
            tint = HushColor.TextSecondary,
            iconSize = 21.dp,
        )
    }
}

@Composable
private fun TimerPill(timer: TimerState?, accent: Color, onClick: () -> Unit) {
    val running = timer != null
    val context = LocalContext.current
    val description = if (timer != null) {
        context.getString(R.string.cd_timer_running, remainingMinutes(timer.remainingMillis))
    } else {
        stringResource(R.string.cd_timer_idle)
    }

    Row(
        Modifier
            .height(34.dp)
            .clip(PillShape)
            .background(if (running) accent.copy(alpha = 0.18f) else HushColor.SurfaceLow)
            .border(1.dp, if (running) accent.copy(alpha = 0.45f) else HushColor.Hairline, PillShape)
            .clickable(
                onClick = onClick,
                role = Role.Button,
                indication = ripple(color = accent),
                interactionSource = null,
            )
            .semantics { contentDescription = description }
            .padding(horizontal = HushSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Bedtime,
            contentDescription = null,
            tint = if (running) accent else HushColor.TextTertiary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (timer != null) formatCountdown(timer.remainingMillis) else stringResource(R.string.timer_idle),
            style = MaterialTheme.typography.labelMedium,
            color = if (running) HushColor.TextPrimary else HushColor.TextSecondary,
        )
    }
}

/** Mix title and the one-line status underneath the orb. */
@Composable
private fun NowPlayingText(state: PlaybackState) {
    val timer = state.timer
    val title = state.mix.title(stringResource(R.string.mix_empty_title))
    val status = when {
        state.mix.isEmpty -> stringResource(R.string.status_empty)
        !state.isPlaying -> stringResource(R.string.status_paused)
        timer != null && timer.isFading ->
            stringResource(R.string.status_fading, formatCountdown(timer.remainingMillis))
        timer != null ->
            stringResource(R.string.status_playing_timer, remainingMinutes(timer.remainingMillis))
        state.isDucked -> stringResource(R.string.status_ducked)
        else -> stringResource(R.string.status_playing)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HushSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = HushColor.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(HushSpacing.sm))
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = HushColor.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SceneRow(activeSceneId: String?, actions: HomeActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HushSpacing.screen),
        horizontalArrangement = Arrangement.spacedBy(HushSpacing.sm),
    ) {
        Scenes.all.forEach { scene ->
            HushChip(
                label = scene.name,
                selected = scene.id == activeSceneId,
                onClick = { actions.onSceneClick(scene.mix) },
                // A scene wears the colour of the sound it leads with.
                accent = accentFor(scene.mix.ids.first()),
            )
        }
    }
}

/**
 * The pinned bottom bar. It fades from the page into an almost-opaque ground
 * so the catalog can scroll under it and stay legible.
 */
@Composable
private fun MasterVolumeBar(
    volume: Float,
    palette: State<MixPalette>,
    onVolumeChange: (Float) -> Unit,
) {
    val scrim = remember {
        Brush.verticalGradient(
            0f to HushColor.NightTop.copy(alpha = 0f),
            0.4f to HushColor.NightTop.copy(alpha = 0.90f),
            1f to HushColor.NightTop.copy(alpha = 0.97f),
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(scrim)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Spacer(Modifier.height(HushSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HushSpacing.screen)
                .padding(bottom = HushSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    volume <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                    volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                },
                contentDescription = null,
                tint = HushColor.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(HushSpacing.md))
            HushSlider(
                value = volume,
                onValueChange = onVolumeChange,
                accent = palette.value.accent,
                label = stringResource(R.string.master_volume),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(HushSpacing.md))
            Text(
                text = formatPercent(volume),
                style = MaterialTheme.typography.labelSmall,
                color = HushColor.TextTertiary,
                modifier = Modifier.width(30.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}
