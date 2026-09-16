package dev.tapio.hush.ui.home

import dev.tapio.hush.ui.home.HomeLayout.Companion.MAX_COLUMNS
import dev.tapio.hush.ui.home.HomeLayout.Companion.MIN_COLUMNS
import dev.tapio.hush.ui.home.HomeLayout.Companion.PLAYBACK_PANE_MAX_DP
import dev.tapio.hush.ui.home.HomeLayout.Companion.PLAYBACK_PANE_MIN_DP
import dev.tapio.hush.ui.home.HomeLayout.Companion.TILE_MIN_WIDTH_DP
import dev.tapio.hush.ui.theme.HushSpacing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class HomeLayoutTest {

    private fun wide(width: Int) = HomeLayout.from(width) as HomeLayout.Wide

    @Test
    fun `every phone width is the compact column`() {
        for (width in listOf(320, 360, 411, 599)) {
            assertEquals(HomeLayout.Compact, HomeLayout.from(width), "width $width")
        }
    }

    @Test
    fun `600 dp is the first wide window and gives a phone-width catalog with three columns`() {
        // 42 % of 600 is 252, clamped up to 280; the catalog pane is then 320 dp,
        // exactly the smallest phone, and keeps its three columns.
        assertEquals(HomeLayout.Wide(playbackPaneDp = 280, catalogColumns = 3), HomeLayout.from(600))
    }

    @Test
    fun `the reference sizes from the spec`() {
        assertEquals(HomeLayout.Wide(328, 3), HomeLayout.from(780), "landscape phone")
        assertEquals(HomeLayout.Wide(336, 3), HomeLayout.from(800), "portrait 10-inch tablet")
        assertEquals(HomeLayout.Wide(353, 4), HomeLayout.from(840), "four columns (from 838 dp)")
        assertEquals(HomeLayout.Wide(437, 5), HomeLayout.from(1040), "five columns")
        assertEquals(HomeLayout.Wide(440, 6), HomeLayout.from(1280), "landscape 10-inch tablet")
    }

    @Test
    fun `column counts change exactly where one more full-size tile fits`() {
        assertEquals(3, wide(837).catalogColumns)
        assertEquals(4, wide(838).catalogColumns)
        assertEquals(4, wide(1033).catalogColumns)
        assertEquals(5, wide(1034).catalogColumns)
        assertEquals(5, wide(1153).catalogColumns)
        assertEquals(6, wide(1154).catalogColumns)
    }

    @Test
    fun `the playback pane clamp lets go at 668 dp and bites again at 1047 dp`() {
        assertEquals(280, wide(667).playbackPaneDp)
        assertEquals(281, wide(668).playbackPaneDp)
        assertEquals(439, wide(1046).playbackPaneDp)
        assertEquals(440, wide(1047).playbackPaneDp)
    }

    @Test
    fun `the playback pane never leaves its clamp and columns never leave theirs`() {
        for (width in 600..2400) {
            val layout = wide(width)
            assertTrue(layout.playbackPaneDp in PLAYBACK_PANE_MIN_DP..PLAYBACK_PANE_MAX_DP, "pane at $width")
            assertTrue(layout.catalogColumns in MIN_COLUMNS..MAX_COLUMNS, "columns at $width")
        }
        assertEquals(PLAYBACK_PANE_MAX_DP, wide(2400).playbackPaneDp)
        assertEquals(MAX_COLUMNS, wide(2400).catalogColumns)
    }

    private val margin = HushSpacing.screen.value.roundToInt()
    private val gutter = HushSpacing.gutter.value.roundToInt()

    private fun tileWidth(width: Int, layout: HomeLayout.Wide): Int {
        val usable = width - layout.playbackPaneDp - 2 * margin
        return (usable - (layout.catalogColumns - 1) * gutter) / layout.catalogColumns
    }

    @Test
    fun `below 652 dp the three-column floor wins and tiles shrink like on a phone`() {
        assertEquals(86, tileWidth(600, wide(600)))
        assertEquals(103, tileWidth(651, wide(651)))
        assertEquals(TILE_MIN_WIDTH_DP, tileWidth(652, wide(652)))
    }

    @Test
    fun `from 652 dp a tile is never narrower than the minimum, and the grid is packed as tight as that allows`() {
        for (width in 652..2400) {
            val layout = wide(width)
            assertTrue(tileWidth(width, layout) >= TILE_MIN_WIDTH_DP, "tile at $width with ${layout.catalogColumns} columns")
            if (layout.catalogColumns < MAX_COLUMNS) {
                val usable = width - layout.playbackPaneDp - 2 * margin
                val oneMore = layout.catalogColumns + 1
                assertTrue(
                    usable + gutter < oneMore * (TILE_MIN_WIDTH_DP + gutter),
                    "$oneMore columns would still fit at $width",
                )
            }
        }
    }

    @Test
    fun `columns only ever grow with the width`() {
        var previous = MIN_COLUMNS
        for (width in 600..2400) {
            val columns = wide(width).catalogColumns
            assertTrue(columns >= previous, "columns fell from $previous to $columns at $width")
            previous = columns
        }
    }
}
