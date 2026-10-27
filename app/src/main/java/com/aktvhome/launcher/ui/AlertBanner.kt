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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aktvhome.launcher.core.guard.Answer
import com.aktvhome.launcher.data.Alert
import com.aktvhome.launcher.data.AlertEvent
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.LayoutStore
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.SettingsStore
import com.aktvhome.launcher.data.Team
import com.aktvhome.launcher.data.WatchNow
import com.aktvhome.launcher.ui.settings.Eyebrow
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.Palettes
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.theme.dayness
import com.aktvhome.launcher.ui.watch.NetImage
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
 * accessibility overlay needs no "draw over apps" permission). A small glass toast, top right
 * under the gear. It takes focus (on its watch button) so the remote can act on it; keys in the
 * first 500 ms are ignored, so a press meant for the app doesn't hit it. It closes after Banner
 * time (5 s by default) without a key, on Back or Dismiss, and the window is removed so focus
 * returns to the app underneath.
 * Alerts that arrive while one shows wait their turn.
 *
 * It also asks about accessibility services switched off outside Haven ([ask]), in the same
 * toast with three answers. Closing that one without an answer (Back, the small close, Home or
 * the timer) means "ask again after the next restart"; [onAnswer] gets the answer either way.
 */
class AlertBanner(
    private val service: AccessibilityService,
    private val store: SettingsStore,
    private val onAnswer: (String, Answer) -> Unit = { _, _ -> },
) {

    /** What the banner shows: an NBA alert, or a question about an accessibility service. */
    private sealed interface Pop {
        class Nba(val alert: Alert) : Pop
        class Ask(val service: String, val label: String) : Pop
    }

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val windows = service.getSystemService(WindowManager::class.java)
    private val layout = LayoutStore(service)
    // Its own Watch Now, for the same watch options as the NBA cards (Live TV channels, ESPN's live row).
    private val watchNow = WatchNow(
        service, main, store,
        liveApps = { layout.layout.collections.firstOrNull { it.id == "livetv" }?.apps?.toSet().orEmpty() },
        labelOf = { pkg -> labelOf(pkg) ?: pkg.substringAfterLast('.') },
    )
    private val queue = ArrayDeque<Pop>()
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var timer: Job? = null
    /** True from picking the next alert until its banner closes. */
    private var busy = false
    /** What closing the banner on screen without an answer means; null for NBA alerts. */
    private var unanswered: (() -> Unit)? = null

    /** Shows [alert] now, or after the banner on screen. Safe from any thread. */
    fun show(alert: Alert) = enqueue(Pop.Nba(alert))

    /** Asks about [service] (named [label]), which was switched off outside Haven. Safe from any thread. */
    fun ask(service: String, label: String) = enqueue(Pop.Ask(service, label))

    private fun enqueue(pop: Pop) {
        main.launch {
            queue.addLast(pop)
            if (!busy) next()
        }
    }

    /** Closes the banner on screen and drops any waiting (Home was pressed); questions count as unanswered. */
    fun dismissAll() {
        main.launch {
            queue.filterIsInstance<Pop.Ask>().forEach { onAnswer(it.service, Answer.REMIND) }
            queue.clear()
            unanswered?.invoke()
            close()
        }
    }

    fun destroy() {
        queue.clear()
        removeWindow()
        main.cancel()
    }

    private suspend fun next() {
        val pop = queue.removeFirstOrNull() ?: return
        busy = true
        store.reload()
        val s = store.settings
        val colors = Palettes.colors(s.theme, dayness(s.dayMode, LocalTime.now()))
        val shown = when (pop) {
            is Pop.Nba -> {
                layout.reload()
                runCatching { watchNow.loadRows() }
                val option = watchOptions(pop.alert.game, sources()).first()
                open(null) { done ->
                    Banner(pop.alert, option, s.hideScores, colors, watchNow, onOpen = { option.open(); done() }, onDismiss = done)
                }
            }
            is Pop.Ask -> open({ onAnswer(pop.service, Answer.REMIND) }) { done ->
                AskBanner(pop.label, colors) { answer ->
                    onAnswer(pop.service, answer ?: Answer.REMIND)
                    done()
                }
            }
        }
        if (!shown) {
            if (pop is Pop.Ask) onAnswer(pop.service, Answer.REMIND)
            busy = false
            next()
        }
    }

    /**
     * Adds the banner window showing [content], which calls its argument once acted on. Closing
     * it any other way (Back, the timer, Home) runs [onUnanswered]. False if the window couldn't
     * be added.
     */
    private fun open(onUnanswered: (() -> Unit)?, content: @Composable (done: () -> Unit) -> Unit): Boolean {
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
                    if (event.action == KeyEvent.ACTION_UP) {
                        unanswered?.invoke()
                        close()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.setViewTreeLifecycleOwner(o)
        root.setViewTreeSavedStateRegistryOwner(o)
        root.addView(ComposeView(service).apply {
            setContent { content { close() } }
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
        unanswered = onUnanswered
        restartTimer()
        return true
    }

    private fun restartTimer() {
        timer?.cancel()
        timer = main.launch {
            delay(store.settings.bannerSeconds * 1_000L)
            unanswered?.invoke()
            close()
        }
    }

    private fun close() {
        if (view == null) return
        unanswered = null
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
    watchNow: WatchNow,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val game = alert.game
    val primary = remember { FocusRequester() }
    NbaToast("Haven · NBA", alert.event.headline, "▶ Watch on ${option.app}", colors, watchNow, primary, onOpen, onDismiss) {
        TeamLine(game.away, hideScores, colors)
        TeamLine(game.home, hideScores, colors)
        val detail = if (hideScores) "${alert.event.label} · tune in" else statusText(game)
        Text(
            listOfNotNull(detail, game.network).joinToString(" · "),
            color = colors.inkSoft, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The three answers, in the order the question shows them. */
internal val ASK_CHOICES = listOf(
    "Turn it back on" to Answer.TURN_ON,
    "Remind me after restart" to Answer.REMIND,
    "Leave it off" to Answer.LEAVE_OFF,
)

/**
 * "[label] was switched off": asked when a service Haven keeps on is switched off outside Haven,
 * or after a restart for one waiting for an answer ([afterRestart]). [onAnswer] gets null for the
 * small close, which counts as no answer.
 */
@Composable
fun AskBanner(label: String, colors: ThemeColors, afterRestart: Boolean = false, modifier: Modifier = Modifier, onAnswer: (Answer?) -> Unit) {
    val primary = remember { FocusRequester() }
    ChoiceToast(
        "Haven · Accessibility", Icons.Rounded.Accessibility,
        if (afterRestart) "$label is still off" else "$label was switched off",
        if (afterRestart) "You asked to be reminded after a restart. Haven keeps it on if you turn it back on."
        else "Haven keeps it on for you. What would you like?",
        ASK_CHOICES.map { it.first }, colors, primary,
        onChoose = { onAnswer(ASK_CHOICES[it].second) },
        onClose = { onAnswer(null) },
        modifier = modifier,
    )
}

/**
 * The small glass toast under the gear, top right in the TV-safe area, a quarter of the screen
 * wide: NBA logo and [eyebrow] with a small close, the [headline], then [body] on the left and the
 * main action ([primary], focused through [focus]) over a quiet Dismiss on the right. Shared by
 * the alert banners and the Last night recap.
 *
 * It takes focus on the main action as it appears, and keeps asking for the first few seconds
 * until it has it: Home may still be loading and move focus, and a new window may not have key
 * focus yet. Once in, the D-pad stays on its three buttons (close, main action, Dismiss) until
 * it closes, so a stray press can't drop you back onto what's behind it.
 */
@Composable
fun NbaToast(
    eyebrow: String,
    headline: String,
    primary: String,
    colors: ThemeColors,
    watchNow: WatchNow,
    focus: FocusRequester,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    val close = remember { FocusRequester() }
    val dismiss = remember { FocusRequester() }
    Column(
        modifier
            .holdingFocus(focus)
            .padding(top = 76.dp, end = 40.dp)
            .width(240.dp)
            .glassPanel(colors, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NetImage(watchNow, Nba.LEAGUE_LOGO, 14.dp)
            Box(Modifier.weight(1f)) { Eyebrow(eyebrow, colors) }
            SmallClose(
                colors, onDismiss,
                Modifier.focusRequester(close).focusProperties { down = focus; up = close; left = close; right = close },
            )
        }
        Text(headline, color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp), content = body)
            Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                WatchPill(
                    primary, colors, focus, onPrimary,
                    Modifier.focusProperties { up = close; down = dismiss; left = focus; right = focus },
                )
                DismissButton(
                    colors, onDismiss,
                    Modifier.focusRequester(dismiss).focusProperties { up = focus; down = dismiss; left = dismiss; right = dismiss },
                )
            }
        }
    }
}

/**
 * A question in the same toast: [icon] and [eyebrow] with a small close, the [headline] and a
 * [line] under it, then [choices] stacked, the first one the main action (focused through
 * [focus]). [onChoose] gets the chosen index; [onClose] is the small close. It takes and holds
 * focus like [NbaToast], and the D-pad stays on its buttons.
 */
@Composable
fun ChoiceToast(
    eyebrow: String,
    icon: ImageVector,
    headline: String,
    line: String,
    choices: List<String>,
    colors: ThemeColors,
    focus: FocusRequester,
    onChoose: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val close = remember { FocusRequester() }
    val others = remember(choices.size) { List(choices.size) { if (it == 0) focus else FocusRequester() } }
    Column(
        modifier
            .holdingFocus(focus)
            .padding(top = 76.dp, end = 40.dp)
            .width(240.dp)
            .glassPanel(colors, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Box(Modifier.weight(1f)) { Eyebrow(eyebrow, colors) }
            SmallClose(
                colors, onClose,
                Modifier.focusRequester(close).focusProperties { down = others.first(); up = close; left = close; right = close },
            )
        }
        Text(headline, color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(line, color = colors.inkSoft, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            choices.forEachIndexed { i, label ->
                val me = others[i]
                val nav = Modifier.focusProperties {
                    up = others.getOrNull(i - 1) ?: close
                    down = others.getOrNull(i + 1) ?: me
                    left = me
                    right = me
                }
                if (i == 0) WatchPill(label, colors, focus, { onChoose(i) }, nav)
                else DismissButton(colors, { onChoose(i) }, Modifier.focusRequester(me).then(nav), label)
            }
        }
    }
}

/**
 * Takes focus on [focus] as the toast appears, and keeps asking for the first few seconds until
 * it has it: Home may still be loading and move focus, and a new window may not have key focus yet.
 */
@Composable
private fun Modifier.holdingFocus(focus: FocusRequester): Modifier {
    var holding by remember { mutableStateOf(false) }
    val host = LocalView.current
    LaunchedEffect(Unit) {
        repeat(FOCUS_TRIES) {
            if (!holding) {
                // A window that's just been added may not have key focus yet.
                if (!host.hasFocus()) host.requestFocus()
                runCatching { focus.requestFocus() }
            }
            delay(FOCUS_RETRY_MS)
        }
    }
    return onFocusChanged { holding = it.hasFocus }
}

@Composable
private fun TeamLine(team: Team, hideScore: Boolean, colors: ThemeColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        val dot = runCatching { Color(android.graphics.Color.parseColor("#${team.color}")) }.getOrDefault(colors.accent)
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(team.name, color = colors.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!hideScore) Text(team.score, color = colors.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The banner's main action, a compact pill; focus fills it. */
@Composable
private fun WatchPill(text: String, colors: ThemeColors, focus: FocusRequester, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(
        text, color = if (focused) colors.pillInk else colors.ink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** Secondary banner action, styled as a proper compact TV button. */
@Composable
private fun DismissButton(colors: ThemeColors, onClick: () -> Unit, modifier: Modifier = Modifier, text: String = "Dismiss") {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(
        text, color = if (focused) colors.pillInk else colors.ink, fontSize = 10.sp,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** A small round close button, the settings panels' close in miniature. */
@Composable
private fun SmallClose(colors: ThemeColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (focused) colors.pill else colors.glass)
            .border(1.dp, colors.edge, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, "Close", tint = if (focused) colors.pillInk else colors.ink, modifier = Modifier.size(12.dp))
    }
}

/** "Q4 2:31", "2OT 0:45", "Halftime", "Final", or "Tip-off 7:30 PM". */
private fun statusText(game: Game): String = when (game.state) {
    GameState.PRE -> "Tip-off " + game.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))
    GameState.FINAL -> "Final"
    GameState.LIVE -> game.clockLine()
}

/** How long a toast keeps asking for focus: [FOCUS_TRIES] times, [FOCUS_RETRY_MS] apart (3 s). */
private const val FOCUS_TRIES = 20
private const val FOCUS_RETRY_MS = 150L
