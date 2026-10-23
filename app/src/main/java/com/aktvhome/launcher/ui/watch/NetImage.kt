package com.aktvhome.launcher.ui.watch

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.aktvhome.launcher.data.WatchNow

/**
 * An image from the web (logos, player photos), loaded through [WatchNow]'s art cache. Takes no
 * space until it arrives, and none at all if it can't be loaded.
 */
@Composable
fun NetImage(watchNow: WatchNow, url: String?, size: Dp, modifier: Modifier = Modifier, shape: Shape = RectangleShape) {
    if (url == null) return
    LaunchedEffect(url) { watchNow.loadArt(url, width = 128) }
    val bmp = watchNow.art[url] ?: return
    Image(bmp, null, modifier.size(size).clip(shape), contentScale = ContentScale.Fit)
}
