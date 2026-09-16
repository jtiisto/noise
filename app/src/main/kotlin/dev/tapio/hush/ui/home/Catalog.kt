package dev.tapio.hush.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tapio.hush.R
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import dev.tapio.hush.ui.icon
import dev.tapio.hush.ui.theme.HushColor
import dev.tapio.hush.ui.theme.HushSize
import dev.tapio.hush.ui.theme.HushSpacing
import dev.tapio.hush.ui.theme.TileShape
import dev.tapio.hush.ui.theme.accentFor
import kotlin.math.ceil

private const val COLUMNS = 3
private const val VOLUME_DOTS = 3

/**
 * One catalog section as a fixed 3-column grid.
 *
 * A plain `Column` of `Row`s rather than a `LazyVerticalGrid`: the catalog is
 * 16 tiles that never change, and a lazy grid inside the page's single
 * vertical scroll would need a fixed height and bring its own scroll
 * container. This composes the whole grid once and lets the page scroll it.
 */
@Composable
fun CatalogSection(
    sounds: List<SoundId>,
    mix: Mix,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HushSpacing.gutter),
    ) {
        sounds.chunked(COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(HushSpacing.gutter)) {
                row.forEach { id ->
                    SoundTile(
                        id = id,
                        gain = mix.layer(id)?.gain,
                        onClick = { actions.onSoundClick(id) },
                        onLongClick = { actions.onSoundLongClick(id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last row's tiles the same width as every other row.
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A catalog tile. [gain] is null when the sound is not in the mix — that
 * single nullable is the whole selection state.
 */
@Composable
private fun SoundTile(
    id: SoundId,
    gain: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = gain != null
    val accent = accentFor(id)
    val context = LocalContext.current

    val colorSpec = remember { tween<Color>(220) }
    val container by animateColorAsState(
        if (selected) accent.copy(alpha = 0.13f) else HushColor.SurfaceLow,
        colorSpec,
        label = "tileContainer",
    )
    val outline by animateColorAsState(
        if (selected) accent.copy(alpha = 0.60f) else HushColor.Hairline,
        colorSpec,
        label = "tileOutline",
    )
    // A small spring on selection: the tile settles rather than snapping.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.97f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tileScale",
    )

    Box(
        modifier
            .height(HushSize.tileHeight)
            // Read inside the layer block so the spring never recomposes the tile.
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(TileShape)
            .background(container)
            .border(if (selected) 1.5.dp else 1.dp, outline, TileShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = Role.Button,
                indication = ripple(color = accent),
                interactionSource = null,
            )
            .semantics {
                this.selected = selected
                contentDescription = context.getString(R.string.cd_sound_tile, id.displayName)
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        // Icon + label are centered together as the tile's optical centre; the
        // volume dots are pinned to the bottom so they never pull that centre
        // up (an empty dots row on unselected tiles otherwise made every tile
        // look top-heavy). A fixed two-line label slot with the text centred in
        // it keeps the icons aligned across a row whether a label is one line
        // ("Rain") or two ("Airplane cabin").
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(HushSize.tileIcon)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (selected) 0.24f else 0.11f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = id.icon,
                    contentDescription = null,
                    tint = if (selected) accent else accent.copy(alpha = 0.72f),
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.height(HushSpacing.sm))
            Box(Modifier.height(LabelSlotHeight), contentAlignment = Alignment.Center) {
                Text(
                    text = id.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) HushColor.TextPrimary else HushColor.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        VolumeDots(
            gain = gain,
            accent = accent,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp),
        )
    }
}

/** Two lines of the label style, so one- and two-word labels leave icons aligned across a row. */
private val LabelSlotHeight = 30.dp

/** Three dots showing roughly how loud a selected layer sits. Blank when unselected. */
@Composable
private fun VolumeDots(gain: Float?, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.height(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (gain != null) {
            val lit = ceil(gain.coerceIn(0f, 1f) * VOLUME_DOTS).toInt()
            repeat(VOLUME_DOTS) { index ->
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = if (index < lit) 0.95f else 0.22f)),
                )
            }
        }
    }
}
