package com.aktvhome.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.aktvhome.launcher.data.Motion

/**
 * Seconds since the scene started, ticking every frame.
 *
 * Ambient scenes run on this instead of Compose animations because those obey the system
 * animator scale, and many TVs (this one included) have it set to 0, which freezes them.
 * Haven's own Motion setting is the switch for this motion. Read the value inside a draw
 * block so only drawing reruns, not composition.
 */
@Composable
fun rememberAmbientSeconds(motion: Motion): State<Float> {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion) {
        if (motion == Motion.OFF) return@LaunchedEffect
        val start = withFrameNanos { it }
        val scale = if (motion == Motion.REDUCED) 0.5f else 1f
        while (true) {
            withFrameNanos { now -> seconds.floatValue = (now - start) / 1_000_000_000f * scale }
        }
    }
    return seconds
}
