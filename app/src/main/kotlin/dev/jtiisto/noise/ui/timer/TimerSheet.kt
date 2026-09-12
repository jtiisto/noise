package dev.jtiisto.noise.ui.timer

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.jtiisto.noise.R
import dev.jtiisto.noise.core.playback.PlaybackSettings
import dev.jtiisto.noise.ui.components.HushBottomSheet
import dev.jtiisto.noise.ui.components.HushChip
import dev.jtiisto.noise.ui.components.HushPrimaryButton
import dev.jtiisto.noise.ui.components.HushSegmentedControl
import dev.jtiisto.noise.ui.components.HushSlider
import dev.jtiisto.noise.ui.components.HushTextButton
import dev.jtiisto.noise.ui.components.SheetTitle
import dev.jtiisto.noise.ui.formatCountdown
import dev.jtiisto.noise.ui.formatDuration
import dev.jtiisto.noise.ui.formatFadeSeconds
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSpacing
import kotlin.math.roundToInt

private const val CUSTOM_STEP_MINUTES = 5

/** How the sheet is currently answering "how long?". */
enum class TimerLengthChoice {
    /** No timer at all — play until the user stops it. */
    UNTIL_CANCELLED,

    /** One of [PlaybackSettings.TIMER_PRESETS_MINUTES]. */
    PRESET,

    /** A length dialled in on the slider. */
    CUSTOM,
}

/** The sleep-timer modal sheet. */
@Composable
fun TimerSheet(
    isRunning: Boolean,
    isPlaying: Boolean,
    remainingMillis: Long?,
    settings: PlaybackSettings,
    accent: Color,
    onStart: (Int) -> Unit,
    onPlayUntilCancelled: () -> Unit,
    onCancel: () -> Unit,
    onFadeSecondsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    HushBottomSheet(onDismissRequest = onDismiss) {
        TimerSheetContent(
            isRunning = isRunning,
            isPlaying = isPlaying,
            remainingMillis = remainingMillis,
            settings = settings,
            accent = accent,
            onStart = onStart,
            onPlayUntilCancelled = onPlayUntilCancelled,
            onCancel = onCancel,
            onFadeSecondsChange = onFadeSecondsChange,
        )
    }
}

/**
 * The sheet body, separated from the modal container so it can be previewed
 * and screenshot-tested (a modal sheet renders into its own window).
 *
 * The chosen length lives here rather than in the ViewModel: it is a draft
 * that only means anything once the primary button is pressed.
 *
 * The three answers to "how long?" form one list, bracketed by the two
 * full-width rows: no limit at the top, pick-your-own at the bottom, the six
 * ready-made lengths between them.
 */
@Composable
fun TimerSheetContent(
    isRunning: Boolean,
    isPlaying: Boolean,
    remainingMillis: Long?,
    settings: PlaybackSettings,
    accent: Color,
    onStart: (Int) -> Unit,
    onPlayUntilCancelled: () -> Unit,
    onCancel: () -> Unit,
    onFadeSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    initialChoice: TimerLengthChoice = initialLengthChoice(settings, isRunning),
) {
    var choice by rememberSaveable { mutableStateOf(initialChoice) }
    var minutes by rememberSaveable { mutableIntStateOf(initialMinutes(settings)) }
    val untilCancelled = choice == TimerLengthChoice.UNTIL_CANCELLED

    Column(modifier.fillMaxWidth()) {
        SheetTitle(
            title = stringResource(R.string.timer_title),
            subtitle = when {
                isRunning && remainingMillis != null ->
                    stringResource(R.string.timer_running_note, formatCountdown(remainingMillis))
                untilCancelled -> stringResource(R.string.timer_subtitle_until_cancelled)
                else -> stringResource(R.string.timer_subtitle)
            },
        )
        Spacer(Modifier.height(HushSpacing.xl))

        HushChip(
            label = stringResource(R.string.timer_until_cancelled),
            selected = untilCancelled,
            onClick = { choice = TimerLengthChoice.UNTIL_CANCELLED },
            accent = accent,
            leadingIcon = Icons.Rounded.AllInclusive,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(HushSpacing.sm))

        PlaybackSettings.TIMER_PRESETS_MINUTES.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HushSpacing.sm),
            ) {
                row.forEach { preset ->
                    HushChip(
                        // Uniform "N min" keeps the grid scannable; the custom
                        // readout below is where hours are spelled out.
                        label = "$preset min",
                        selected = choice == TimerLengthChoice.PRESET && minutes == preset,
                        onClick = {
                            choice = TimerLengthChoice.PRESET
                            minutes = preset
                        },
                        accent = accent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(HushSpacing.sm))
        }

        HushChip(
            label = stringResource(R.string.timer_custom),
            selected = choice == TimerLengthChoice.CUSTOM,
            onClick = { choice = TimerLengthChoice.CUSTOM },
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = choice == TimerLengthChoice.CUSTOM,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(HushSpacing.lg))
                Text(
                    text = formatDuration(minutes),
                    style = MaterialTheme.typography.displaySmall,
                    color = HushColor.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                HushSlider(
                    value = customFraction(minutes),
                    onValueChange = { minutes = customMinutes(it) },
                    accent = accent,
                    label = stringResource(R.string.cd_timer_custom_length),
                    valueDescription = formatDuration(minutes),
                )
            }
        }

        // Nothing fades when nothing is going to stop, so the control leaves
        // with the timer rather than sitting there inert.
        AnimatedVisibility(
            visible = !untilCancelled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(HushSpacing.xl))
                Text(
                    text = stringResource(R.string.timer_fade_label).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = HushColor.TextTertiary,
                )
                Spacer(Modifier.height(HushSpacing.sm))
                HushSegmentedControl(
                    options = PlaybackSettings.FADE_OPTIONS_SECONDS,
                    selected = settings.fadeOutSeconds,
                    onSelect = onFadeSecondsChange,
                    label = ::formatFadeSeconds,
                    accent = accent,
                )
            }
        }

        Spacer(Modifier.height(HushSpacing.xl))
        HushPrimaryButton(
            label = stringResource(timerPrimaryLabel(untilCancelled, isPlaying, isRunning)),
            onClick = { if (untilCancelled) onPlayUntilCancelled() else onStart(minutes) },
            accent = accent,
        )
        if (isRunning) {
            Spacer(Modifier.height(HushSpacing.xs))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                HushTextButton(
                    label = stringResource(R.string.timer_cancel),
                    onClick = onCancel,
                    color = HushColor.TextSecondary,
                )
            }
        }
    }
}

/**
 * What the primary button promises. "Keep playing" rather than "Play until
 * cancelled" when sound is already coming out, because the button is then
 * confirming a state rather than starting one.
 */
@StringRes
internal fun timerPrimaryLabel(untilCancelled: Boolean, isPlaying: Boolean, isTimerRunning: Boolean): Int = when {
    untilCancelled && isPlaying -> R.string.timer_keep_playing
    untilCancelled -> R.string.timer_play_until_cancelled
    isTimerRunning -> R.string.timer_update
    else -> R.string.timer_start
}

/**
 * Which row the sheet opens on. A running timer always wins — showing "Until
 * cancelled" over a live countdown would be a lie — otherwise the remembered
 * last choice decides.
 */
internal fun initialLengthChoice(settings: PlaybackSettings, isTimerRunning: Boolean): TimerLengthChoice = when {
    !isTimerRunning && settings.prefersUntilCancelled -> TimerLengthChoice.UNTIL_CANCELLED
    // Ask the same question the presets will: which chip does the length the
    // sheet is about to show sit on?
    initialMinutes(settings) in PlaybackSettings.TIMER_PRESETS_MINUTES -> TimerLengthChoice.PRESET
    else -> TimerLengthChoice.CUSTOM
}

/**
 * The length the presets and slider start from. `lastTimerMinutes` can be the
 * until-cancelled sentinel (0), which is not a length, so fall back to the
 * default rather than clamping it up to the 5-minute floor.
 */
internal fun initialMinutes(settings: PlaybackSettings): Int =
    if (settings.prefersUntilCancelled) {
        PlaybackSettings().lastTimerMinutes
    } else {
        settings.lastTimerMinutes.coerceIn(
            PlaybackSettings.TIMER_MIN_MINUTES,
            PlaybackSettings.TIMER_MAX_MINUTES,
        )
    }

/** 5..480 minutes in 5-minute steps, mapped onto the slider's 0..1. */
private val CUSTOM_STEPS =
    (PlaybackSettings.TIMER_MAX_MINUTES - PlaybackSettings.TIMER_MIN_MINUTES) / CUSTOM_STEP_MINUTES

internal fun customFraction(minutes: Int): Float {
    val span = (PlaybackSettings.TIMER_MAX_MINUTES - PlaybackSettings.TIMER_MIN_MINUTES).toFloat()
    return ((minutes - PlaybackSettings.TIMER_MIN_MINUTES) / span).coerceIn(0f, 1f)
}

internal fun customMinutes(fraction: Float): Int =
    PlaybackSettings.TIMER_MIN_MINUTES +
        (fraction.coerceIn(0f, 1f) * CUSTOM_STEPS).roundToInt() * CUSTOM_STEP_MINUTES
