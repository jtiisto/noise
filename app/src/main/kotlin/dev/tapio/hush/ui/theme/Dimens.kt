package dev.tapio.hush.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Spacing scale. One number per role so screens stay in step with each other. */
object HushSpacing {
    /** Left/right screen margin. */
    val screen = 20.dp
    val gutter = 10.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Sizes that more than one component needs to agree on. */
object HushSize {
    /** Drawing box for the play orb; the visible control inside it is 168 dp. */
    val orb = 200.dp
    val tileHeight = 104.dp
    val tileIcon = 42.dp
    val minTouchTarget = 48.dp
}

/** 16 dp cards, 999 dp pills, per the spec. */
val HushShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val PillShape = RoundedCornerShape(999.dp)
val TileShape = RoundedCornerShape(18.dp)
val CardShape = RoundedCornerShape(16.dp)
val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
