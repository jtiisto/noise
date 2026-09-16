package dev.tapio.hush.ui.home

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PopoverPositionTest {

    private val provider = BelowAnchorEnd(endPaddingPx = 12, gapPx = 4)
    private val header = IntRect(left = 0, top = 0, right = 360, bottom = 80)
    private val window = IntSize(360, 780)
    private val card = IntSize(280, 60)

    @Test
    fun `hangs under the anchor, flush with its end margin`() {
        assertEquals(IntOffset(x = 360 - 12 - 280, y = 84), provider.calculatePosition(header, window, LayoutDirection.Ltr, card))
    }

    @Test
    fun `mirrors to the start margin in RTL`() {
        assertEquals(IntOffset(x = 12, y = 84), provider.calculatePosition(header, window, LayoutDirection.Rtl, card))
    }

    @Test
    fun `follows an anchor that does not start at the window origin`() {
        // A header pushed down by the status bar and inset from a side cutout.
        val inset = IntRect(left = 40, top = 60, right = 400, bottom = 140)
        assertEquals(IntOffset(x = 400 - 12 - 280, y = 144), provider.calculatePosition(inset, IntSize(440, 780), LayoutDirection.Ltr, card))
        assertEquals(IntOffset(x = 52, y = 144), provider.calculatePosition(inset, IntSize(440, 780), LayoutDirection.Rtl, card))
    }

    @Test
    fun `a card that exactly fits the window sits at its origin`() {
        assertEquals(IntOffset(0, 84), provider.calculatePosition(IntRect(0, 0, 292, 80), IntSize(292, 600), LayoutDirection.Ltr, IntSize(280, 60)))
    }

    @Test
    fun `a card wider than the window pins its origin to the start edge`() {
        val narrow = IntSize(300, 600)
        assertEquals(IntOffset(0, 84), provider.calculatePosition(IntRect(0, 0, 300, 80), narrow, LayoutDirection.Ltr, IntSize(320, 60)))
        assertEquals(IntOffset(0, 84), provider.calculatePosition(IntRect(0, 0, 300, 80), narrow, LayoutDirection.Rtl, IntSize(320, 60)))
    }

    @Test
    fun `in a short window the card moves up so its bottom stays inside`() {
        assertEquals(IntOffset(x = 360 - 12 - 280, y = 120 - 60), provider.calculatePosition(header, IntSize(360, 120), LayoutDirection.Ltr, card))
        // Taller than the window: pin to the top rather than to nothing.
        assertEquals(IntOffset(x = 360 - 12 - 280, y = 0), provider.calculatePosition(header, IntSize(360, 50), LayoutDirection.Ltr, card))
    }
}
