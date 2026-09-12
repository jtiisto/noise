package dev.jtiisto.noise.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jtiisto.noise.R
import dev.jtiisto.noise.core.model.SoundLayer
import dev.jtiisto.noise.ui.components.HushIconButton
import dev.jtiisto.noise.ui.components.HushSlider
import dev.jtiisto.noise.ui.components.HushTextButton
import dev.jtiisto.noise.ui.formatPercent
import dev.jtiisto.noise.ui.icon
import dev.jtiisto.noise.ui.theme.CardShape
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSpacing
import dev.jtiisto.noise.ui.theme.accentFor

/**
 * The per-layer mixer. Adding or removing a layer changes the card's height;
 * `animateContentSize` is what makes that a movement rather than a jump.
 */
@Composable
fun MixCard(
    layers: List<SoundLayer>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HushColor.SurfaceLow)
            .border(1.dp, HushColor.Hairline, CardShape)
            .animateContentSize()
            .padding(horizontal = HushSpacing.lg)
            .padding(top = HushSpacing.md, bottom = HushSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.mix_card_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = HushColor.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            HushTextButton(
                label = stringResource(R.string.mix_clear),
                onClick = actions::onClearMix,
                color = HushColor.TextSecondary,
                // Nudged outwards so the label, not its tap padding, lines up
                // with the card's right edge.
                modifier = Modifier.offset(x = HushSpacing.md),
            )
        }
        layers.forEach { layer ->
            LayerRow(layer = layer, actions = actions)
        }
    }
}

@Composable
private fun LayerRow(layer: SoundLayer, actions: HomeActions) {
    val id = layer.id
    val accent = accentFor(id)
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(id.icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(HushSpacing.md))
            Text(
                text = id.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = HushColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatPercent(layer.gain),
                style = MaterialTheme.typography.labelSmall,
                color = HushColor.TextTertiary,
            )
        }
        // The remove button rides the slider's row rather than the label's:
        // both want a 48 dp target, and sharing one keeps a three-layer card
        // from eating half the screen.
        Row(verticalAlignment = Alignment.CenterVertically) {
            HushSlider(
                value = layer.gain,
                onValueChange = { actions.onLayerGainChange(id, it) },
                accent = accent,
                label = context.getString(R.string.cd_layer_volume, id.displayName),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(HushSpacing.sm))
            HushIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = context.getString(R.string.cd_remove_layer, id.displayName),
                onClick = { actions.onRemoveLayer(id) },
                tint = HushColor.TextTertiary,
                size = 32.dp,
                iconSize = 17.dp,
            )
        }
    }
}
