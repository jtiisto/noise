package dev.jtiisto.noise.ui.timer

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

/** The sleep-timer modal sheet. */
@Composable
fun TimerSheet(
    isRunning: Boolean,
    remainingMillis: Long?,
    settings: PlaybackSettings,
    accent: Color,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onFadeSecondsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    HushBottomSheet(onDismissRequest = onDismiss) {
        TimerSheetContent(
            isRunning = isRunning,
            remainingMillis = remainingMillis,
            settings = settings,
            accent = accent,
            onStart = onStart,
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
 * that only means anything once "Start timer" is pressed.
 */
@Composable
fun TimerSheetContent(
    isRunning: Boolean,
    remainingMillis: Long?,
    settings: PlaybackSettings,
    accent: Color,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onFadeSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    startCustom: Boolean = settings.lastTimerMinutes !in PlaybackSettings.TIMER_PRESETS_MINUTES,
) {
    var minutes by rememberSaveable { mutableIntStateOf(settings.lastTimerMinutes) }
    var customOpen by rememberSaveable { mutableStateOf(startCustom) }
    val presets = PlaybackSettings.TIMER_PRESETS_MINUTES

    Column(modifier.fillMaxWidth()) {
        SheetTitle(
            title = stringResource(R.string.timer_title),
            subtitle = if (isRunning && remainingMillis != null) {
                stringResource(R.string.timer_running_note, formatCountdown(remainingMillis))
            } else {
                stringResource(R.string.timer_subtitle)
            },
        )
        Spacer(Modifier.height(HushSpacing.xl))

        presets.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HushSpacing.sm),
            ) {
                row.forEach { preset ->
                    HushChip(
                        // Uniform "N min" keeps the grid scannable; the custom
                        // readout below is where hours are spelled out.
                        label = "$preset min",
                        selected = !customOpen && minutes == preset,
                        onClick = {
                            customOpen = false
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
            selected = customOpen,
            onClick = { customOpen = true },
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = customOpen,
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

        Spacer(Modifier.height(HushSpacing.xl))
        HushPrimaryButton(
            label = stringResource(if (isRunning) R.string.timer_update else R.string.timer_start),
            onClick = { onStart(minutes) },
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
