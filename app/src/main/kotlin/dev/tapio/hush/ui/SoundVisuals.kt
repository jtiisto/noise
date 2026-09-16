package dev.tapio.hush.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.Shower
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.Toys
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.ui.graphics.vector.ImageVector
import dev.tapio.hush.core.model.SoundId

/**
 * One icon per catalog entry. The noise colours have no picture of their own,
 * so they borrow the shape of what they sound like: speckle for white, a soft
 * blur for pink, heavy low bars for brown, scattered points for blue (the same
 * blue noise used for dithering), a sparkle for violet and a half-toned disc
 * for the flat-sounding grey.
 */
val SoundId.icon: ImageVector
    get() = when (this) {
        SoundId.WHITE -> Icons.Rounded.Grain
        SoundId.PINK -> Icons.Rounded.BlurOn
        SoundId.BROWN -> Icons.Rounded.GraphicEq
        SoundId.BLUE -> Icons.Rounded.ScatterPlot
        SoundId.VIOLET -> Icons.Rounded.AutoAwesome
        SoundId.GREY -> Icons.Rounded.Tonality

        SoundId.RAIN -> Icons.Rounded.WaterDrop
        SoundId.HEAVY_RAIN -> Icons.Rounded.Shower
        SoundId.THUNDERSTORM -> Icons.Rounded.Thunderstorm
        SoundId.OCEAN -> Icons.Rounded.Waves
        SoundId.WIND -> Icons.Rounded.Air
        SoundId.CAMPFIRE -> Icons.Rounded.LocalFireDepartment
        SoundId.STREAM -> Icons.Rounded.Water
        SoundId.CRICKETS -> Icons.Rounded.Nightlight

        SoundId.FAN -> Icons.Rounded.Toys
        SoundId.AIRPLANE -> Icons.Rounded.Flight
    }
