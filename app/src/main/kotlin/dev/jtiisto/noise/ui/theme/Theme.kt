package dev.jtiisto.noise.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Placeholder palette; the UI implementation owns the real design tokens
// (see specs/ui.md).
private val Night = darkColorScheme(
    background = Color(0xFF0B0F1A),
    surface = Color(0xFF141A2E),
    primary = Color(0xFF7FA6FF),
    onBackground = Color(0xFFECEBF5),
    onSurface = Color(0xFFECEBF5),
)

@Composable
fun HushTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Night, content = content)
}
