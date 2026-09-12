package dev.jtiisto.noise.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.jtiisto.noise.R
import dev.jtiisto.noise.core.playback.PlaybackSettings
import dev.jtiisto.noise.ui.components.HushBottomSheet
import dev.jtiisto.noise.ui.components.HushSegmentedControl
import dev.jtiisto.noise.ui.components.SectionHeader
import dev.jtiisto.noise.ui.components.SheetTitle
import dev.jtiisto.noise.ui.formatFadeSeconds
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSpacing

/** The settings modal sheet. */
@Composable
fun SettingsSheet(
    settings: PlaybackSettings,
    accent: Color,
    onMixWithOtherAppsChange: (Boolean) -> Unit,
    onFadeSecondsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    HushBottomSheet(onDismissRequest = onDismiss) {
        SettingsSheetContent(
            settings = settings,
            accent = accent,
            versionName = remember(context) { context.versionName() },
            onMixWithOtherAppsChange = onMixWithOtherAppsChange,
            onFadeSecondsChange = onFadeSecondsChange,
        )
    }
}

/**
 * The sheet body. [versionName] is passed in rather than read from the
 * context so previews and screenshot references stay deterministic.
 */
@Composable
fun SettingsSheetContent(
    settings: PlaybackSettings,
    accent: Color,
    versionName: String,
    onMixWithOtherAppsChange: (Boolean) -> Unit,
    onFadeSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SheetTitle(title = stringResource(R.string.settings_title))
        Spacer(Modifier.height(HushSpacing.xl))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_mix_with_others),
                    style = MaterialTheme.typography.titleMedium,
                    color = HushColor.TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_mix_with_others_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = HushColor.TextSecondary,
                )
            }
            Spacer(Modifier.width(HushSpacing.lg))
            Switch(
                checked = settings.mixWithOtherApps,
                onCheckedChange = onMixWithOtherAppsChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = HushColor.Foreground,
                    checkedTrackColor = accent.copy(alpha = 0.55f),
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = HushColor.TextTertiary,
                    uncheckedTrackColor = HushColor.SurfaceMid,
                    uncheckedBorderColor = HushColor.Hairline,
                ),
            )
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
        SectionHeader(stringResource(R.string.settings_about))
        Spacer(Modifier.height(HushSpacing.md))
        Text(
            text = stringResource(R.string.settings_version, versionName),
            style = MaterialTheme.typography.titleMedium,
            color = HushColor.TextPrimary,
        )
        Spacer(Modifier.height(HushSpacing.sm))
        Text(
            text = stringResource(R.string.settings_synthesis_note),
            style = MaterialTheme.typography.bodySmall,
            color = HushColor.TextSecondary,
        )
        Spacer(Modifier.height(HushSpacing.lg))
        Text(
            text = stringResource(R.string.settings_licences).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = HushColor.TextTertiary,
        )
        Spacer(Modifier.height(HushSpacing.sm))
        Text(
            text = stringResource(R.string.settings_licences_body),
            style = MaterialTheme.typography.bodySmall,
            color = HushColor.TextTertiary,
        )
    }
}

/** `versionName` is nullable on some OEM builds and absent in previews. */
private fun Context.versionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull() ?: "—"
