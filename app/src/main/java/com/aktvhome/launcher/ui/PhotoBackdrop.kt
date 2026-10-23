package com.aktvhome.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A wallpaper photo behind a theme's layout. A dark gradient (stronger at the top and bottom,
 * where the text and rows sit, and stronger at night) keeps the light text readable on any photo.
 */
@Composable
fun BoxScope.PhotoBackdrop(photo: ImageBitmap, dayness: Float, credit: String?) {
    Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    val night = 1f - dayness
    Box(
        Modifier
            .fillMaxSize()
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
