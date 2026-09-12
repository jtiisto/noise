package dev.jtiisto.noise.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSpacing
import dev.jtiisto.noise.ui.theme.PillShape

private const val SELECTION_ANIM_MILLIS = 220

/**
 * A pill that can be on or off: scenes, timer presets, "Custom".
 *
 * The visual height stays at 38 dp — a row of six of them has to fit a 320 dp
 * screen — while `minimumInteractiveComponentSize` keeps the touch target at
 * 48 dp.
 */
@Composable
fun HushChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val spec = remember { tween<Color>(SELECTION_ANIM_MILLIS) }
    val container by animateColorAsState(
        if (selected) accent.copy(alpha = 0.20f) else HushColor.SurfaceLow,
        spec,
        label = "chipContainer",
    )
    val outline by animateColorAsState(
        if (selected) accent.copy(alpha = 0.55f) else HushColor.Hairline,
        spec,
        label = "chipOutline",
    )
    val content by animateColorAsState(
        if (selected) HushColor.TextPrimary else HushColor.TextSecondary,
        spec,
        label = "chipContent",
    )

    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(38.dp)
            .clip(PillShape)
            .background(container)
            .border(1.dp, outline, PillShape)
            .clickable(
                onClick = onClick,
                role = Role.Button,
                indication = ripple(color = accent),
                interactionSource = null,
            )
            // The label is the accessible name; `selected` is what TalkBack
            // adds on top of it.
            .semantics { this.selected = selected }
            .padding(horizontal = HushSpacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (selected) accent else content,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(HushSpacing.sm))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * An equal-width choice strip. Used for the fade-out window in both sheets,
 * which is one shared setting, so it must look identical in both places.
 */
@Composable
fun <T> HushSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(PillShape)
            .background(HushColor.SurfaceLow)
            .border(1.dp, HushColor.Hairline, PillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val spec = remember { tween<Color>(SELECTION_ANIM_MILLIS) }
        options.forEach { option ->
            val isSelected = option == selected
            val container by animateColorAsState(
                if (isSelected) accent.copy(alpha = 0.24f) else Color.Transparent,
                spec,
                label = "segmentContainer",
            )
            val content by animateColorAsState(
                if (isSelected) HushColor.TextPrimary else HushColor.TextSecondary,
                spec,
                label = "segmentContent",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(PillShape)
                    .background(container)
                    .clickable(
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                        indication = ripple(color = accent),
                        interactionSource = null,
                    )
                    .semantics {
                        this.selected = isSelected
                        this.contentDescription = label(option)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The one solid, high-contrast button in the app: "Start timer". */
@Composable
fun HushPrimaryButton(
    label: String,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Disabled falls back to an outline rather than a dimmed fill: a
    // half-opacity accent behind dark label text reads as muddy, not inactive.
    val fill = remember(accent, enabled) {
        if (enabled) {
            Brush.verticalGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.78f)))
        } else {
            SolidColor(HushColor.SurfaceLow)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(PillShape)
            .background(fill)
            .then(if (enabled) Modifier else Modifier.border(1.dp, HushColor.Hairline, PillShape))
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                indication = ripple(color = HushColor.NightTop),
                interactionSource = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) HushColor.NightTop else HushColor.TextTertiary,
        )
    }
}

/** A quiet text action: "Clear", "Cancel timer". */
@Composable
fun HushTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = HushColor.TextSecondary,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(PillShape)
            .clickable(
                onClick = onClick,
                role = Role.Button,
                indication = ripple(color = color),
                interactionSource = null,
            )
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(horizontal = HushSpacing.md, vertical = HushSpacing.sm)
            .defaultMinSize(minHeight = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * Uppercase section label with a hairline running to the right edge — the rule
 * carries the "this section starts here" information so the label can stay
 * small and quiet.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = HushColor.TextTertiary,
        )
        Spacer(Modifier.width(HushSpacing.md))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = HushColor.Hairline,
        )
    }
}

/** A round, borderless icon button that keeps a 48 dp target. */
@Composable
fun HushIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier
            .size(size)
            .minimumInteractiveComponentSize()
            .clip(PillShape)
            .clickable(
                onClick = onClick,
                role = Role.Button,
                indication = ripple(bounded = false, radius = 24.dp),
                interactionSource = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Centred title used at the top of every modal sheet. */
@Composable
fun SheetTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = HushColor.TextPrimary,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(HushSpacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = HushColor.TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
