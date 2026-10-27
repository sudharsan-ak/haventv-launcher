package com.aktvhome.launcher.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aktvhome.launcher.core.corner.CornerKind
import com.aktvhome.launcher.core.guard.Answer
import com.aktvhome.launcher.data.AccessibilityGuard
import com.aktvhome.launcher.ui.theme.ThemeColors
import kotlinx.coroutines.delay

/**
 * After a restart, asks again about each accessibility service you said to be reminded about (or
 * didn't answer for), one at a time, on Haven's Home once the TV has settled and the service's app
 * has loaded. Not over other apps: it isn't urgent. An answer decides; closing it without one
 * (Back, the small close, the timer) asks again after the next restart. Only a reminder that was
 * actually shown counts. Keys in the first 500 ms are ignored, and it closes after Banner time
 * without a key.
 */
@Composable
fun AccessibilityReminder(state: HavenState, colors: ThemeColors) {
    val context = LocalContext.current
    val life by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var service by remember { mutableStateOf<String?>(null) }

    val home = life.isAtLeast(Lifecycle.State.RESUMED) && state.overlay == Overlay.NONE &&
        state.menuFor == null && !state.backup.offerRestore && !state.wallpaperView &&
        (state.setup.offered || com.aktvhome.launcher.data.Setup.needed(state.device.status).isEmpty())
    // state.now ticks every 15 s, so a service whose app loads late is picked up while Home sits
    // open; Corner.version changes when the top-right corner comes free. Reminders are the least
    // urgent toast there, so they go after any alert, question or recap.
    LaunchedEffect(home, state.now, Corner.version, service) {
        if (!home || service != null) return@LaunchedEffect
        val next = AccessibilityGuard.reminders(context).firstOrNull() ?: return@LaunchedEffect
        if (!Corner.tryStart(CornerKind.REMINDER)) return@LaunchedEffect
        state.popupShowing = true
        service = next
    }
    // Leaving Home while it's up: it wasn't really seen, so it comes back next time Home is in front.
    LaunchedEffect(life) {
        if (service != null && !life.isAtLeast(Lifecycle.State.RESUMED)) {
            service = null
            state.popupShowing = false
            Corner.finish(CornerKind.REMINDER)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (service != null) {
                state.popupShowing = false
                Corner.finish(CornerKind.REMINDER)
            }
        }
    }

    val s = service ?: return
    val label = remember(s) { AccessibilityGuard.label(context, s) }
    val shownAt = remember(s) { SystemClock.uptimeMillis() }
    // Keys whose press came in the first 500 ms; their release is swallowed too.
    val ignored = remember(s) { mutableSetOf<Int>() }
    var lastKey by remember(s) { mutableLongStateOf(shownAt) }
    // Focus goes back to Home's first row, as after a Home press.
    fun finish(answer: Answer?) {
        if (answer == null) AccessibilityGuard.reminded(context, s) else AccessibilityGuard.answer(context, s, answer)
        service = null
        state.popupShowing = false
        Corner.finish(CornerKind.REMINDER)
        state.homePresses++
    }
    BackHandler { finish(null) }
    LaunchedEffect(s, lastKey) {
        delay(state.settingsStore.settings.bannerSeconds * 1_000L)
        finish(null)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        AskBanner(
            label, colors, afterRestart = true,
            modifier = Modifier.onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val now = SystemClock.uptimeMillis()
                when {
                    e.type == KeyEventType.KeyDown && now - shownAt < 500 -> { ignored += code; true }
                    e.type == KeyEventType.KeyUp && ignored.remove(code) -> true
                    else -> {
                        lastKey = now
                        if (code == KeyEvent.KEYCODE_BACK) {
                            if (e.type == KeyEventType.KeyUp) finish(null)
                            true
                        } else false
                    }
                }
            },
            onAnswer = ::finish,
        )
    }
}
