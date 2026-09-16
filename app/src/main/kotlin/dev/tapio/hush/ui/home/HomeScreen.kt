package dev.tapio.hush.ui.home

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AllInclusive
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tapio.hush.R
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundCategory
import dev.tapio.hush.core.model.SoundId
import dev.tapio.hush.core.model.Scenes
import dev.tapio.hush.core.playback.PlaybackState
import dev.tapio.hush.core.playback.TimerState
import dev.tapio.hush.ui.components.HushIconButton
import dev.tapio.hush.ui.components.HushChip
import dev.tapio.hush.ui.components.HushSlider
import dev.tapio.hush.ui.components.SectionHeader
import dev.tapio.hush.ui.components.auroraBackground
import dev.tapio.hush.ui.components.rememberMixPalette
import dev.tapio.hush.ui.critter.critterFor
import dev.tapio.hush.ui.critterscenes.CritterScene
import dev.tapio.hush.ui.formatCountdown
import dev.tapio.hush.ui.formatPercent
import dev.tapio.hush.ui.remainingMinutes
import dev.tapio.hush.ui.settings.SettingsSheet
import dev.tapio.hush.ui.theme.HushColor
import dev.tapio.hush.ui.theme.HushSize
import dev.tapio.hush.ui.theme.HushSpacing
import dev.tapio.hush.ui.theme.MixPalette
import dev.tapio.hush.ui.theme.PillShape
import dev.tapio.hush.ui.theme.WordmarkStyle
import dev.tapio.hush.ui.theme.accentFor
import dev.tapio.hush.ui.timer.TimerSheet

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
 * On a phone one vertical scroll holds everything between the fixed header
 * and the pinned master-volume bar, so the orb scrolls away with the catalog
 * rather than the catalog scrolling inside a window of its own. From 600 dp
 * the body splits into two panes instead (see [HomeLayout]); the header and
 * the bar stay full-width in both.
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

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .auroraBackground(palette),
    ) {
        // Side insets — a landscape phone's navigation bar, a cutout — come
        // off the usable width before the split is decided, and are padded
        // once around the shared column. windowInsetsPadding consumes what it
        // pads, so the bar's own navigation-bar padding stays bottom-only,
        // and the aurora behind stays edge-to-edge.
        val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val insetWidth = with(density) {
            (sideInsets.getLeft(this, layoutDirection) + sideInsets.getRight(this, layoutDirection)).toDp()
        }
        // The usable width alone picks the phone column or the two-pane
        // split; the header and the volume bar are shared by both.
        val layout = HomeLayout.from((maxWidth - insetWidth).value.toInt())
        // Hoisted above the split so a fold or a resize across 600 dp keeps
        // every scroll position instead of throwing the page back to the top.
        val compactScroll = rememberScrollState()
        val playbackScroll = rememberScrollState()
        val catalogScroll = rememberScrollState()
        val scenesScroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(sideInsets),
        ) {
            HomeHeader(
                timer = timer,
                palette = palette,
                onTimerClick = actions::onTimerPillClick,
                onSettingsClick = actions::onSettingsClick,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )

            Box(Modifier.weight(1f)) {
                when (layout) {
                    HomeLayout.Compact -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(compactScroll),
                    ) {
                        PlaybackContent(state, ui, actions, palette, onPlaybackRequested)
                        CatalogContent(mix = mix, actions = actions, scenesScroll = scenesScroll)
                    }
                    is HomeLayout.Wide -> Row(Modifier.fillMaxSize()) {
                        // Centred while it fits; once a three-layer mix card
                        // makes it taller than the window it scrolls from the top.
                        Column(
                            Modifier
                                .width(layout.playbackPaneDp.dp)
                                .fillMaxHeight()
                                .verticalScroll(playbackScroll),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            PlaybackContent(state, ui, actions, palette, onPlaybackRequested)
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(catalogScroll),
                        ) {
                            Spacer(Modifier.height(HushSpacing.md))
                            CatalogContent(
                                mix = mix,
                                actions = actions,
                                scenesScroll = scenesScroll,
                                columns = layout.catalogColumns,
                            )
                        }
                    }
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
            isPlaying = state.isPlaying,
            remainingMillis = timer?.remainingMillis,
            settings = state.settings,
            accent = palette.value.accent,
            onStart = { minutes ->
                onPlaybackRequested()
                actions.onStartTimer(minutes)
            },
            onPlayUntilCancelled = {
                onPlaybackRequested()
                actions.onPlayUntilCancelled()
            },
            onCancel = actions::onCancelTimer,
            onFadeSecondsChange = actions::onFadeSecondsChange,
            onDismiss = actions::onSheetDismiss,
        )
        HomeSheet.Settings -> SettingsSheet(
            settings = state.settings,
            accent = palette.value.accent,
            crashReport = ui.crashReport,
            onMixWithOtherAppsChange = actions::onMixWithOtherAppsChange,
            onFadeSecondsChange = actions::onFadeSecondsChange,
            onShareCrashReport = {
                ui.crashReport?.let { shareCrashReport(context, it) }
                actions.onShareCrashReport()
            },
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

/**
 * Everything about the current mix: the one-time crash notice, the orb with
 * its critter scene, the title and status line, and the mix card. The phone
 * column stacks this above [CatalogContent]; the wide layout gives it a pane.
 */
@Composable
private fun ColumnScope.PlaybackContent(
    state: PlaybackState,
    ui: HomeUiState,
    actions: HomeActions,
    palette: State<MixPalette>,
    onPlaybackRequested: () -> Unit,
) {
    val context = LocalContext.current
    val mix = state.mix
    Spacer(Modifier.height(HushSpacing.md))

    // Sits above the orb rather than over it: the app still works, and the
    // user came here to start a sound.
    AnimatedVisibility(
        visible = ui.crashNoticeVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column {
            CrashNoticeCard(
                onShare = {
                    ui.crashReport?.let { shareCrashReport(context, it) }
                    actions.onShareCrashReport()
                },
                onDismiss = actions::onDismissCrashReport,
                modifier = Modifier.padding(horizontal = HushSpacing.screen),
            )
            Spacer(Modifier.height(HushSpacing.lg))
        }
    }

    // The orb and its critter scene share one 200 dp box, so the layout is
    // unchanged. The scene is a purely DECORATIVE sibling drawn ABOVE the orb
    // (later in the Box) and placed right of centre at the orb's base: it
    // shows on top of the play glyph yet — being a bare Canvas with no
    // pointer modifier — never consumes touches, so the whole orb stays
    // tappable under it.
    Box(Modifier.align(Alignment.CenterHorizontally).size(HushSize.orb)) {
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
        )
        CritterScene(
            kind = critterFor(mix),
            isPlaying = state.isPlaying,
            palette = palette,
            // Lifted so the lowest art (cat cushion, frog puddle, firefly
            // grass) clears the mix title below the orb; still right of
            // centre and overlapping the orb.
            size = 126.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = 50.dp, y = (-8).dp),
        )
    }

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
}

/** The scenes row and the three catalog sections, [columns] tiles to a row. */
@Composable
private fun CatalogContent(
    mix: Mix,
    actions: HomeActions,
    scenesScroll: ScrollState,
    columns: Int = PHONE_COLUMNS,
) {
    SectionHeader(
        title = stringResource(R.string.scenes_header),
        modifier = Modifier.padding(horizontal = HushSpacing.screen),
    )
    Spacer(Modifier.height(HushSpacing.md))
    SceneRow(activeSceneId = Scenes.matching(mix)?.id, actions = actions, scrollState = scenesScroll)

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
            columns = columns,
        )
        Spacer(Modifier.height(HushSpacing.xl))
    }
    Spacer(Modifier.height(HushSpacing.sm))
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
        stringResource(R.string.cd_timer_none)
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
        // The pill always states the current mode, so "no timer" reads as a
        // deliberate choice rather than an absence: a moon and a countdown
        // while one runs, the infinity glyph and "No timer" while none does.
        Icon(
            imageVector = if (running) Icons.Rounded.Bedtime else Icons.Rounded.AllInclusive,
            contentDescription = null,
            tint = if (running) accent else HushColor.TextTertiary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (timer != null) formatCountdown(timer.remainingMillis) else stringResource(R.string.timer_none),
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
    // Which of the six the app is doing is decided by a pure function; this
    // only turns the answer into words.
    val status = when (playbackStatus(state)) {
        PlaybackStatus.EMPTY -> stringResource(R.string.status_empty)
        PlaybackStatus.PAUSED -> stringResource(R.string.status_paused)
        PlaybackStatus.FADING ->
            stringResource(R.string.status_fading, formatCountdown(timer!!.remainingMillis))
        PlaybackStatus.TIMER ->
            stringResource(R.string.status_playing_timer, remainingMinutes(timer!!.remainingMillis))
        PlaybackStatus.DUCKED -> stringResource(R.string.status_ducked)
        PlaybackStatus.UNTIL_CANCELLED -> stringResource(R.string.status_playing_until_cancelled)
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
private fun SceneRow(activeSceneId: String?, actions: HomeActions, scrollState: ScrollState) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
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
