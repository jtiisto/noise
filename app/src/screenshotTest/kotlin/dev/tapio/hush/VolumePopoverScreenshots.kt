package dev.tapio.hush

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.tapio.hush.core.model.SoundId
import dev.tapio.hush.ui.home.MasterVolumePopoverContent
import dev.tapio.hush.ui.preview.PreviewStates
import dev.tapio.hush.ui.theme.HushColor
import dev.tapio.hush.ui.theme.HushTheme
import dev.tapio.hush.ui.theme.accentFor

/**
 * The volume popover's body, as the header hangs it: a `Popup` renders in
 * its own window, so this is what a reference can show (`specs/ui.md`,
 * Home item 6).
 */
@PreviewTest
@Preview(widthDp = 360, heightDp = 120, showBackground = true, backgroundColor = 0xFF0B0F1AL)
@Composable
fun MasterVolumePopover() {
    HushTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(HushColor.NightTop)
                .padding(horizontal = 12.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            MasterVolumePopoverContent(
                volume = PreviewStates.playingTrio.masterVolume,
                accent = accentFor(SoundId.RAIN),
                onVolumeChange = {},
            )
        }
    }
}
