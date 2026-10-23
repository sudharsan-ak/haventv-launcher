package com.aktvhome.launcher.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aktvhome.launcher.data.Alert
import com.aktvhome.launcher.data.AlertEvent
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.LayoutStore
import com.aktvhome.launcher.data.SettingsStore
import com.aktvhome.launcher.data.Team
import com.aktvhome.launcher.data.WatchNow
import com.aktvhome.launcher.ui.settings.Eyebrow
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.Palettes
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.theme.dayness
import com.aktvhome.launcher.ui.watch.WatchOption
import com.aktvhome.launcher.ui.watch.WatchSources
import com.aktvhome.launcher.ui.watch.watchOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * NBA alert banners, drawn by the Home button service over whatever app is in front (an
 * accessibility overlay needs no "draw over apps" permission). Top right, in Haven's glass.
 * The banner takes focus (on its watch button) so the remote can act on it; keys in the first
 * 500 ms are ignored, so a press meant for the app doesn't hit it. It closes after 5 s without a
 * key, on Back or Dismiss, and the window is removed so focus returns to the app underneath.
 * Alerts that arrive while one shows wait their turn.
 */
class AlertBanner(private val service: AccessibilityService, private val store: SettingsStore) {

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val windows = service.getSystemService(WindowManager::class.java)
    private val layout = LayoutStore(service)
    // Its own Watch Now, for the same watch options as the NBA cards (Live TV channels, ESPN's live row).
    private val watchNow = WatchNow(
        service, main, store,
        liveApps = { layout.layout.collections.firstOrNull { it.id == "livetv" }?.apps?.toSet().orEmpty() },
        labelOf = { pkg -> labelOf(pkg) ?: pkg.substringAfterLast('.') },
    )
    private val queue = ArrayDeque<Alert>()
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var timer: Job? = null
    /** True from picking the next alert until its banner closes. */
    private var busy = false

    /** Shows [alert] now, or after the banner on screen. Safe from any thread. */
    fun show(alert: Alert) {
        main.launch {
            queue.addLast(alert)
            if (!busy) next()
        }
    }

    /** Closes the banner on screen and drops any waiting (Home was pressed). */
    fun dismissAll() {
        main.launch {
            queue.clear()
            close()
        }
    }

    fun destroy() {
        queue.clear()
        removeWindow()
        main.cancel()
    }

    private suspend fun next() {
        val alert = queue.removeFirstOrNull() ?: return
        busy = true
        store.reload()
        layout.reload()
        runCatching { watchNow.loadRows() }
        val option = watchOptions(alert.game, sources()).first()
        val s = store.settings
        val colors = Palettes.colors(s.theme, dayness(s.dayMode, LocalTime.now()))
        if (!open(alert, option, s.hideScores, colors)) {
            busy = false
            next()
        }
    }

    private fun open(alert: Alert, option: WatchOption, hideScores: Boolean, colors: ThemeColors): Boolean {
        val shownAt = SystemClock.uptimeMillis()
        // Keys whose press came in the first 500 ms; their release is swallowed too.
        val ignored = mutableSetOf<Int>()
        val o = OverlayOwner().also { it.start() }
        val root = object : FrameLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && SystemClock.uptimeMillis() - shownAt < 500) {
                    ignored += event.keyCode
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP && ignored.remove(event.keyCode)) return true
                restartTimer()
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.setViewTreeLifecycleOwner(o)
        root.setViewTreeSavedStateRegistryOwner(o)
        root.addView(ComposeView(service).apply {
            setContent {
                Banner(alert, option, hideScores, colors, onOpen = { option.open(); close() }, onDismiss = { close() })
            }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END }
        val added = runCatching { windows.addView(root, params) }.isSuccess
        if (!added) {
            o.stop()
            return false
        }
        view = root
        owner = o
        restartTimer()
        return true
    }

    private fun restartTimer() {
        timer?.cancel()
        timer = main.launch {
            delay(5_000)
            close()
        }
    }

    private fun close() {
        if (view == null) return
        removeWindow()
        busy = false
        if (queue.isNotEmpty()) main.launch { if (!busy) next() }
    }

    private fun removeWindow() {
        timer?.cancel()
        view?.let { v -> runCatching { windows.removeView(v) } }
        owner?.stop()
        view = null
        owner = null
    }

    private fun sources() = WatchSources(
        labelOf = ::labelOf,
        localStations = store.settings.localStations,
        live = watchNow.live,
        appRows = watchNow.appRows,
        openItem = watchNow::open,
        openApp = { pkg -> openApp(service, pkg) },
        openGame = watchNow::openGame,
    )

    /** An installed app's name, or null. */
    private fun labelOf(pkg: String): String? = runCatching {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}

private fun openApp(context: Context, pkg: String) {
    val pm = context.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** What Compose needs from a window's owner, for a view that has no activity. */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

@Composable
private fun Banner(
    alert: Alert,
    option: WatchOption,
    hideScores: Boolean,
    colors: ThemeColors,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val game = alert.game
    val primary = remember { FocusRequester() }
    // Inside the TV-safe area: about 5% in from the top and right edges.
    Column(
        Modifier
            .padding(top = 32.dp, end = 48.dp)
            .width(420.dp)
            .glassPanel(colors, RoundedCornerShape(24.dp), alpha = 0.9f)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow("Haven · NBA", colors)
        Text(alert.event.headline, color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        TeamLine(game.away, hideScores, colors)
        TeamLine(game.home, hideScores, colors)
        val detail = if (hideScores) "${alert.event.label} · tune in" else statusText(game)
        Text(listOfNotNull(detail, game.network).joinToString(" · "), color = colors.inkSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(option.label, colors, focusRequester = primary, onClick = onOpen)
            Pill("Dismiss", colors, onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { primary.requestFocus() } }
}

@Composable
private fun TeamLine(team: Team, hideScore: Boolean, colors: ThemeColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val dot = runCatching { Color(android.graphics.Color.parseColor("#${team.color}")) }.getOrDefault(colors.accent)
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(dot))
        Text(team.name, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (!hideScore) Text(team.score, color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** "Q4 2:31", "2OT 0:45", "Halftime", "Final", or "Tip-off 7:30 PM". */
private fun statusText(game: Game): String = when {
    game.state == GameState.PRE -> "Tip-off " + game.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))
    game.state == GameState.FINAL -> "Final"
    game.halftime -> "Halftime"
    else -> {
        val p = game.period
        val name = when {
            p <= 4 -> "Q$p"
            p == 5 -> "OT"
            else -> "${p - 4}OT"
        }
        "$name ${game.clock}".trim()
    }
}
