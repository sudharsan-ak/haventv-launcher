package com.aktvhome.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Motion
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.ThemeColors
import kotlinx.coroutines.delay

/**
 * A wallpaper photo behind a theme's layout. A dark gradient (stronger at the top and bottom,
 * where the text and rows sit, and stronger at night) keeps the light text readable on any photo.
 * In wallpaper view the gradient fades with the layout ([tint]), so the photo shows as it is.
 */
@Composable
fun BoxScope.PhotoBackdrop(photo: ImageBitmap, dayness: Float, credit: String?, tint: Float = 1f) {
    Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    val night = 1f - dayness
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = tint }
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f + 0.15f * night),
                    0.45f to Color.Black.copy(alpha = 0.20f + 0.20f * night),
                    1f to Color.Black.copy(alpha = 0.70f + 0.10f * night),
                ),
            ),
    )
    if (!credit.isNullOrBlank()) {
        Text(
            credit, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 10.dp),
        )
    }
}

/**
 * How much of Home's layout shows: 1, or 0 in wallpaper view (Back on Home; any key brings it
 * back). Fades unless Motion is off.
 */
@Composable
fun homeShown(state: HavenState): Float {
    val off = state.settingsStore.settings.motion == Motion.OFF
    val shown by animateFloatAsState(if (state.wallpaperView) 0f else 1f, tween(if (off) 0 else 400), label = "homeShown")
    return shown
}

/** On entering wallpaper view, a small frosted line says how to get back; gone after 3 s. */
@Composable
fun WallpaperHint(state: HavenState, colors: ThemeColors) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.wallpaperView) {
        visible = state.wallpaperView
        if (visible) {
            delay(3_000)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(400), label = "wallpaperHint")
    if (alpha == 0f) return
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.BottomCenter) {
        Text(
            "Press any button to bring Haven back", color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 40.dp).glassPanel(colors, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
