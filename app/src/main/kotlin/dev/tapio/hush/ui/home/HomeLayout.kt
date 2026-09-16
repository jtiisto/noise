package dev.tapio.hush.ui.home

import androidx.compose.runtime.Immutable
import dev.tapio.hush.ui.theme.HushSpacing
import kotlin.math.roundToInt

/**
 * Which of Home's two layouts a window gets, decided by its width alone.
 *
 * [Compact] is every phone in portrait: the single scrolling column, unchanged.
 * [Wide] is anything 600 dp and up — Material's medium lower bound — where the
 * body splits into a playback pane and a catalog pane that scroll on their
 * own. The numbers live in a pure function so they are unit-tested rather
 * than eyeballed; the composable only maps the answer to a tree.
 */
@Immutable
sealed interface HomeLayout {
    data object Compact : HomeLayout

    /**
     * The playback pane is [playbackPaneDp] wide; the catalog pane takes the
     * rest and shows [catalogColumns] tiles to a row.
     */
    data class Wide(val playbackPaneDp: Int, val catalogColumns: Int) : HomeLayout

    companion object {
        /** Material's medium width class starts here; anything below is a phone. */
        const val WIDE_MIN_WIDTH_DP = 600

        /** The playback pane's share of the window, and where the clamp bites. */
        const val PLAYBACK_PANE_FRACTION = 0.42f
        const val PLAYBACK_PANE_MIN_DP = 280
        const val PLAYBACK_PANE_MAX_DP = 440

        /**
         * From 652 dp a tile is never narrower than this; below it the
         * three-column floor wins and tiles shrink exactly as on a phone
         * (87 dp at 600 dp, whose 320 dp catalog pane *is* the smallest
         * phone). One more column comes whenever one more such tile fits:
         * four from 838 dp, five from 1034 dp, six from 1154 dp.
         */
        const val TILE_MIN_WIDTH_DP = 104
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 6

        fun from(windowWidthDp: Int): HomeLayout {
            if (windowWidthDp < WIDE_MIN_WIDTH_DP) return Compact
            val playbackPane = (windowWidthDp * PLAYBACK_PANE_FRACTION).roundToInt()
                .coerceIn(PLAYBACK_PANE_MIN_DP, PLAYBACK_PANE_MAX_DP)
            val margin = HushSpacing.screen.value.roundToInt()
            val gutter = HushSpacing.gutter.value.roundToInt()
            val usable = windowWidthDp - playbackPane - 2 * margin
            val columns = ((usable + gutter) / (TILE_MIN_WIDTH_DP + gutter)).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
            return Wide(playbackPaneDp = playbackPane, catalogColumns = columns)
        }
    }
}
