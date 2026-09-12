package dev.jtiisto.noise

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.jtiisto.noise.ui.theme.HushTheme

@PreviewTest
@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PlaceholderScreenshot() {
    HushTheme { Placeholder() }
}
