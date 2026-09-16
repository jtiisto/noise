package dev.tapio.hush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.core.model.SoundId
import dev.tapio.hush.ui.components.auroraBackground
import dev.tapio.hush.ui.components.rememberMixPalette
import dev.tapio.hush.ui.critter.critterFor
import dev.tapio.hush.ui.critterscenes.CritterScene
import dev.tapio.hush.ui.home.PlayOrb
import dev.tapio.hush.ui.theme.HushColor
import dev.tapio.hush.ui.theme.HushSize
import dev.tapio.hush.ui.theme.HushTheme
import dev.tapio.hush.ui.theme.WordmarkStyle

/**
 * Google Play listing art, rendered by the same harness as the screen
 * references so it is reproducible and never drifts from the app. The
 * `device` specs pin 160 dpi, so 1 dp = 1 px and the PNGs come out at the
 * exact sizes Play asks for. See docs/release.md, **Store assets**.
 */

/** The 512 × 512 hi-res icon: the adaptive icon's full canvas, Play applies its own mask. */
@PreviewTest
@Preview(device = "spec:width=512px,height=512px,dpi=160")
@Composable
fun StoreIcon512() {
    Box(
        Modifier
            .fillMaxSize()
            .background(colorResource(R.color.hush_icon_background)),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The 1024 × 500 feature graphic: wordmark and promise on the left, the orb with its frog on the right. */
@PreviewTest
@Preview(device = "spec:width=1024px,height=500px,dpi=160")
@Composable
fun StoreFeatureGraphic() {
    val mix = Mix.of(SoundId.RAIN to 0.7f, SoundId.BROWN to 0.4f)
    HushTheme {
        val palette = rememberMixPalette(mix)
        Row(
            Modifier
                .fillMaxSize()
                .auroraBackground(palette)
                .padding(horizontal = 96.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "HUSH",
                    style = WordmarkStyle.copy(fontSize = 84.sp, letterSpacing = 18.sp),
                    color = HushColor.TextPrimary,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Sleep sounds, synthesized as they play.",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
                    color = HushColor.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No recordings, no loops, no network. Nothing to buy.",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                    color = HushColor.TextSecondary,
                )
            }
            Spacer(Modifier.width(48.dp))
            Box(Modifier.size(HushSize.orb)) {
                PlayOrb(
                    isPlaying = true,
                    enabled = true,
                    palette = palette,
                    contentDescription = "",
                    onClick = {},
                )
                CritterScene(
                    kind = critterFor(mix),
                    isPlaying = true,
                    palette = palette,
                    size = 126.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(x = 50.dp, y = (-8).dp),
                )
            }
        }
    }
}
