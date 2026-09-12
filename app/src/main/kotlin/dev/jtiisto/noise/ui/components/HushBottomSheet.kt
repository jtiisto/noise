package dev.jtiisto.noise.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSpacing
import dev.jtiisto.noise.ui.theme.SheetShape

/**
 * The one modal-sheet container the app uses, so the timer, settings and
 * sound sheets are identical in colour, corner and inset handling.
 *
 * Content composables are kept separate from this wrapper: a `ModalBottomSheet`
 * lives in its own window, which Compose Preview screenshots cannot capture,
 * so the previews render the same content inside [SheetPreviewFrame] instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HushBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = HushColor.NightSheet,
        contentColor = HushColor.TextPrimary,
        scrimColor = HushColor.Scrim,
        dragHandle = { BottomSheetDefaults.DragHandle(color = HushColor.HairlineStrong) },
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = HushSpacing.screen)
                .padding(bottom = HushSpacing.xl, top = HushSpacing.sm),
        ) {
            content()
        }
    }
}
