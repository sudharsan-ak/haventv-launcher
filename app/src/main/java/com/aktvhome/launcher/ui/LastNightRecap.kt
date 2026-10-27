package com.aktvhome.launcher.ui

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.LastNight
import com.aktvhome.launcher.data.LastNightToast
import com.aktvhome.launcher.ui.theme.ThemeColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/**
 * The morning catch-up: the first time Haven's Home is in front each day after 6 AM (no overlay
 * open), a toast with last night's result, drawn in Haven's own window like the alert banners and
 * never over other apps. Only from a complete, fresh scoreboard with at least one final. "See
 * recaps" opens the NBA panel on yesterday with that game selected. It holds focus while it's up
 * (Home leaves focus alone, see [HavenState.popupShowing]). Keys in the first 500 ms are ignored,
 * Back dismisses (wherever focus is), and it closes after 10 s without a key.
 */
@Composable
fun LastNightRecap(state: HavenState, colors: ThemeColors) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("last_night", Context.MODE_PRIVATE) }
    val s = state.settingsStore.settings
    val w = state.watchNow
    val life by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var toast by remember { mutableStateOf<LastNightToast?>(null) }

    val home = life.isAtLeast(Lifecycle.State.RESUMED) && state.overlay == Overlay.NONE &&
        state.menuFor == null && !state.backup.offerRestore && !state.wallpaperView &&
        (state.setup.offered || com.aktvhome.launcher.data.Setup.needed(state.device.status).isEmpty())
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    // state.now ticks every 15 s, so 6 AM and a new day are noticed while Home sits open.
    val ready = s.lastNightRecap && home && state.now >= LocalTime.of(6, 0) && prefs.getString("shown", null) != today.toString()

    // Yesterday's scoreboard, fetched within the last 10 minutes (it's been final since the night).
    LaunchedEffect(ready, yesterday) { if (ready) w.loadDay(yesterday, maxAgeMs = 10 * 60_000L) }
    val games = w.games[yesterday]
    LaunchedEffect(ready, games) {
        if (!ready || toast != null || games == null) return@LaunchedEffect
        // Never from old or failed data, or from a night that isn't over.
        if (System.currentTimeMillis() - w.fetchedAt(yesterday) > 10 * 60_000L) return@LaunchedEffect
        if (games.any { it.state == GameState.LIVE }) return@LaunchedEffect
        val pick = LastNight.pick(games, s.favoriteTeam, s.followedTeams, s.hideScores) ?: return@LaunchedEffect
        prefs.edit().putString("shown", today.toString()).apply()
        state.popupShowing = true
        toast = pick
    }
    DisposableEffect(Unit) { onDispose { state.popupShowing = false } }

    val t = toast ?: return
    val focus = remember(t) { FocusRequester() }
    val shownAt = remember(t) { SystemClock.uptimeMillis() }
    // Keys whose press came in the first 500 ms; their release is swallowed too.
    val ignored = remember(t) { mutableSetOf<Int>() }
    var lastKey by remember(t) { mutableLongStateOf(shownAt) }
    // Focus goes back to Home's first row, as after a Home press.
    fun dismiss() {
        toast = null
        state.popupShowing = false
        state.homePresses++
    }
    // Closes it even if focus somehow isn't on it; declared after Home's own Back, so it comes first.
    BackHandler { dismiss() }
    LaunchedEffect(t, lastKey) {
        delay(10_000)
        dismiss()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        NbaToast(
            "Haven · Last night", t.headline, "▶ See recaps", colors, w, focus,
            onPrimary = {
                toast = null
                state.popupShowing = false
                state.openGame(t.game, onSummary = true)
            },
            onDismiss = ::dismiss,
            modifier = Modifier.onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val now = SystemClock.uptimeMillis()
                when {
                    e.type == KeyEventType.KeyDown && now - shownAt < 500 -> { ignored += code; true }
                    e.type == KeyEventType.KeyUp && ignored.remove(code) -> true
                    else -> {
                        lastKey = now
                        if (code == KeyEvent.KEYCODE_BACK) {
                            if (e.type == KeyEventType.KeyUp) dismiss()
                            true
                        } else false
                    }
                }
            },
        ) {
            t.detail?.let { Text(it, color = colors.inkSoft, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
