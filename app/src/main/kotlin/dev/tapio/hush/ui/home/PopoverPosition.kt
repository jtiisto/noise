package dev.tapio.hush.ui.home

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/**
 * Hangs a popover just below its anchor, flush with the anchor's end margin
 * (the header's, for the volume card), mirrored in RTL. The origin is kept
 * inside the window on both axes: a card wider or taller than the window
 * pins to its start or top edge rather than to nothing.
 */
internal class BelowAnchorEnd(
    private val endPaddingPx: Int,
    private val gapPx: Int,
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Rtl) {
            anchorBounds.left + endPaddingPx
        } else {
            anchorBounds.right - endPaddingPx - popupContentSize.width
        }
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), (anchorBounds.bottom + gapPx).coerceIn(0, maxY))
    }
}
