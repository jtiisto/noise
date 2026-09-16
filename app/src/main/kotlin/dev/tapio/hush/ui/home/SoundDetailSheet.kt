package dev.tapio.hush.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tapio.hush.R
import dev.tapio.hush.core.model.SoundId
import dev.tapio.hush.ui.components.HushBottomSheet
import dev.tapio.hush.ui.components.HushPrimaryButton
import dev.tapio.hush.ui.components.HushSlider
import dev.tapio.hush.ui.components.HushTextButton
import dev.tapio.hush.ui.formatPercent
import dev.tapio.hush.ui.icon
import dev.tapio.hush.ui.theme.HushColor
import dev.tapio.hush.ui.theme.HushSpacing
import dev.tapio.hush.ui.theme.accentFor

/** What a long press on a catalog tile opens. */
@Composable
fun SoundDetailSheet(
    id: SoundId,
    gain: Float?,
    mixIsFull: Boolean,
    actions: HomeActions,
    onDismiss: () -> Unit,
) {
    HushBottomSheet(onDismissRequest = onDismiss) {
        SoundDetailSheetContent(
            id = id,
            gain = gain,
            mixIsFull = mixIsFull,
            onGainChange = { actions.onLayerGainChange(id, it) },
            onAdd = { actions.onSoundClick(id) },
            onRemove = { actions.onRemoveLayer(id) },
        )
    }
}

/**
 * [gain] is the layer's volume when the sound is in the mix and null when it
 * is not — which is also what decides whether this sheet offers a slider or
 * an "Add to mix" button.
 */
@Composable
fun SoundDetailSheetContent(
    id: SoundId,
    gain: Float?,
    mixIsFull: Boolean,
    onGainChange: (Float) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = accentFor(id)
    val context = LocalContext.current

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(id.icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(HushSpacing.lg))
            Column(Modifier.weight(1f)) {
                Text(
                    text = id.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = HushColor.TextPrimary,
                )
                Spacer(Modifier.height(HushSpacing.xs))
                Text(
                    text = id.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = HushColor.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(HushSpacing.xl))

        if (gain != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sound_volume).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = HushColor.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatPercent(gain),
                    style = MaterialTheme.typography.labelMedium,
                    color = HushColor.TextSecondary,
                )
            }
            HushSlider(
                value = gain,
                onValueChange = onGainChange,
                accent = accent,
                label = context.getString(R.string.cd_layer_volume, id.displayName),
            )
            Spacer(Modifier.height(HushSpacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                HushTextButton(
                    label = stringResource(R.string.sound_remove),
                    onClick = onRemove,
                    color = HushColor.TextSecondary,
                )
            }
        } else {
            HushPrimaryButton(
                label = stringResource(R.string.sound_add),
                onClick = onAdd,
                accent = accent,
                enabled = !mixIsFull,
            )
            if (mixIsFull) {
                Spacer(Modifier.height(HushSpacing.md))
                Text(
                    text = stringResource(R.string.sound_mix_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = HushColor.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
