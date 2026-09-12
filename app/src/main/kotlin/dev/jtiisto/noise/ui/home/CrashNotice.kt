package dev.jtiisto.noise.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jtiisto.noise.R
import dev.jtiisto.noise.ui.components.HushTextButton
import dev.jtiisto.noise.ui.theme.CardShape
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSpacing

/**
 * The one-time card under the header after a crash. Deliberately built from
 * the same surface, hairline and text buttons as the mix card: this is news,
 * not an alarm, and the night ground has no room for a red banner.
 */
@Composable
fun CrashNoticeCard(
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HushColor.SurfaceLow)
            .border(1.dp, HushColor.Hairline, CardShape)
            .padding(horizontal = HushSpacing.lg)
            .padding(top = HushSpacing.md, bottom = HushSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = HushColor.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(HushSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.crash_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = HushColor.TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.crash_notice_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = HushColor.TextSecondary,
                )
            }
        }
        Row(
            // Nudged outwards so the last label, not its tap padding, lines up
            // with the card's right edge — the same trick the mix card uses.
            Modifier
                .fillMaxWidth()
                .offset(x = HushSpacing.md),
            horizontalArrangement = Arrangement.End,
        ) {
            HushTextButton(
                label = stringResource(R.string.crash_share),
                onClick = onShare,
                color = HushColor.TextPrimary,
            )
            HushTextButton(
                label = stringResource(R.string.crash_dismiss),
                onClick = onDismiss,
                color = HushColor.TextSecondary,
            )
        }
    }
}

/**
 * Hands the report to the share sheet as plain text. The whole point is that
 * it lands in a chat or a mail draft, so it travels as `EXTRA_TEXT` and needs
 * no `FileProvider`.
 */
fun shareCrashReport(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_share_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.crash_share_chooser)),
    )
}
