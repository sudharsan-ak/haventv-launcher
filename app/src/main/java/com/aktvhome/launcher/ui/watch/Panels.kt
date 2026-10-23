@file:OptIn(ExperimentalLayoutApi::class) // FlowRow for channel grids

package com.aktvhome.launcher.ui.watch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.LiveGroup
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.TvItem
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.onKeyDown
import com.aktvhome.launcher.ui.settings.CloseButton
import com.aktvhome.launcher.ui.settings.Eyebrow
import com.aktvhome.launcher.ui.settings.GlassCard
import com.aktvhome.launcher.ui.settings.HavenOverlay
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val YOUTUBE_TV = "com.google.android.youtube.tvunplugged"
private val weekDayFmt = DateTimeFormatter.ofPattern("EEE d")

/** Focus handles for the week strip's day chips, so the games list can return Up to the selected day. */
class DayFocus {
    private val map = mutableMapOf<LocalDate, FocusRequester>()
    fun of(d: LocalDate): FocusRequester = map.getOrPut(d) { FocusRequester() }
}

/**
 * Today · ‹ seven days › · Calendar. The arrows move the week; picking a date in the calendar
 * moves the week to it, so a picked date always sits in its place among its neighbors.
 */
@Composable
fun WeekStrip(state: HavenState, colors: ThemeColors, day: LocalDate, focus: FocusRequester?, days: DayFocus, onDay: (LocalDate) -> Unit) {
    val today = state.watchNow.today()
    var weekStart by remember { mutableStateOf(if (day < today || day > today.plusDays(6)) day else today) }
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("Today", colors, selected = day == today, focusRequester = focus) { weekStart = today; onDay(today) }
        Pill("‹", colors) { weekStart = weekStart.minusDays(7) }
        (0L..6L).map { weekStart.plusDays(it) }.forEach { d ->
            // Dates only; the Today chip in front already says "Today".
            val label = if (d == today.plusDays(1)) "Tomorrow" else d.format(weekDayFmt)
            Pill(label, colors, selected = d == day, focusRequester = days.of(d), onFocused = { onDay(d) }) { onDay(d) }
        }
        Pill("›", colors) { weekStart = weekStart.plusDays(7) }
        Pill("Calendar", colors) { picking = true }
    }
    if (picking) DatePickerDialog(colors, day, onPick = { weekStart = it; onDay(it); picking = false }, onDismiss = { picking = false })
}

/** Up arrow goes to [target] instead of the geometrically nearest item. */
fun upTo(target: FocusRequester): Modifier =
    Modifier.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_UP) { runCatching { target.requestFocus() } }

/** Centered glass panel used by the single-card views. */
@Composable
private fun Panel(colors: ThemeColors, title: String, subtitle: String, onClose: () -> Unit, width: Int = 860, content: @Composable () -> Unit) {
    HavenOverlay(dim = 0.45f, onDismiss = onClose) {
        Column(
            Modifier.align(Alignment.Center).width(width.dp).fillMaxHeight(0.88f)
                .glassPanel(colors, RoundedCornerShape(28.dp), alpha = 0.92f)
                .verticalScroll(rememberScrollState())
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Haven / Watch now", colors)
                    Text(title, color = colors.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = colors.inkSoft, fontSize = 12.sp)
                }
                CloseButton(colors, onClick = onClose)
            }
            content()
        }
    }
}

/** NBA card: one game up top (with ways to watch), the week strip, and that day's games. */
@Composable
fun NbaPanel(state: HavenState, colors: ThemeColors) {
    val w = state.watchNow
    val fav = state.settingsStore.settings.favoriteTeam
    var day by remember { mutableStateOf(w.today()) }
    val first = remember { FocusRequester() }
    val days = remember { DayFocus() }
    LaunchedEffect(day) { w.loadDay(day) }
    val top = if (day == w.today()) w.featured() else w.games[day]?.firstOrNull()
    Panel(colors, "NBA", "Scores from ESPN · games open where they air", { state.overlay = Overlay.NONE }) {
        if (top != null) FeaturedGame(state, colors, top, first)
        WeekStrip(state, colors, day, if (top == null) first else null, days) { day = it }
        val list = w.games[day]
        when {
            list == null && w.nbaError -> Text("Couldn't reach ESPN. Check the connection and try again.", color = colors.inkSoft, fontSize = 12.sp)
            list == null -> Text("Loading games…", color = colors.inkSoft, fontSize = 12.sp)
            list.isEmpty() -> Text("No NBA games on ${day.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))}.", color = colors.inkSoft, fontSize = 12.sp)
            // Up from the first game goes back to the selected day, not the nearest chip above.
            // OK opens the game's popup (watch buttons, Dismiss) right where you are.
            else -> list.forEachIndexed { i, g ->
                GameRow(g, fav, colors, modifier = if (i == 0) upTo(days.of(day)) else Modifier) { state.detailGame = g }
            }
        }
        if (fav.isNotBlank()) {
            val next = w.nextFavorite()
            GlassCard(colors, Modifier.fillMaxWidth(), onClick = { state.scheduleOpen = true }) { ink ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Nba.teamName(fav)}" + (next?.let { " · Next: ${shortDate(it)} ${opponent(it, fav)} ${it.status}" } ?: ""),
                        color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Text("Full schedule ›", color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun FeaturedGame(state: HavenState, colors: ThemeColors, g: Game, focus: FocusRequester) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.glass)
            .border(1.dp, colors.edge, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow(listOfNotNull(if (g.state == GameState.LIVE) "Live" else null, shortDate(g), g.network).joinToString(" · "), colors)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamBlock(g.away.abbr, g.away.name, g.away.score, g.away.color, g.state, Alignment.Start, colors, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(g.status, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                listOfNotNull(g.away.leader, g.home.leader).takeIf { g.state != GameState.PRE }?.let {
                    Text(it.joinToString("  ·  "), color = colors.inkSoft, fontSize = 11.sp)
                }
            }
            TeamBlock(g.home.abbr, g.home.name, g.home.score, g.home.color, g.state, Alignment.End, colors, Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.watchOptions(g).forEachIndexed { i, o ->
                Pill(if (i == 0) "▶  ${o.label}" else o.label, colors, selected = i == 0, focusRequester = if (i == 0) focus else null) {
                    o.open(); state.overlay = Overlay.NONE
                }
            }
            if (g.state != GameState.PRE) Pill("Box score", colors) { state.detailGame = g }
        }
    }
}

@Composable
private fun TeamBlock(abbr: String, name: String, score: String, color: String, st: GameState, align: Alignment.Horizontal, colors: ThemeColors, modifier: Modifier) {
    Column(modifier, horizontalAlignment = align) {
        Text(abbr, color = teamColor(color), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(name, color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (st != GameState.PRE) Text(score, color = colors.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    }
}

/** Live TV card: live NBA games pinned, then each Live TV app's channels A to Z, plus its full guide. */
@Composable
fun LivePanel(state: HavenState, colors: ThemeColors) {
    val w = state.watchNow
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { w.tick(force = true) }
    Panel(colors, "Live TV", "Channels your live TV apps share · OK tunes in", { state.overlay = Overlay.NONE }) {
        val liveGames = if (state.settingsStore.settings.watchNba) w.games[w.today()]?.filter { it.state == GameState.LIVE }.orEmpty() else emptyList()
        if (liveGames.isNotEmpty()) {
            Text("Live games", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            liveGames.forEach { g -> GameRow(g, state.settingsStore.settings.favoriteTeam, colors) { state.detailGame = g } }
        }
        LiveGrid(state, colors, first)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** Channel tiles for each Live TV app, A to Z, with an Open full guide tile for YouTube TV. */
@Composable
fun LiveGrid(state: HavenState, colors: ThemeColors, first: FocusRequester?) {
    val groups = state.watchNow.live
    if (groups.isEmpty()) {
        Text("No live channels yet. Channels shared by apps in your Live TV collection show up here.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    var firstUsed = false
    groups.forEach { group: LiveGroup ->
        Text(state.apps[group.pkg]?.label ?: group.provider, color = colors.inkSoft, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            group.items.forEach { item ->
                ChannelTile(state, colors, item, if (!firstUsed) { firstUsed = true; first } else null)
            }
            if (group.pkg == YOUTUBE_TV) {
                GuideTile(colors) {
                    state.watchNow.open(TvItem(YOUTUBE_TV, "Guide", "", null, false, "https://tv.youtube.com/live?launch=launcher"))
                    state.overlay = Overlay.NONE
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(state: HavenState, colors: ThemeColors, item: TvItem, focus: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    LaunchedEffect(item.art) { state.watchNow.loadArt(item.art) }
    val img = item.art?.let { state.watchNow.art[it] }
    Column(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .width(150.dp).height(112.dp)
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable { state.watchNow.open(item); state.overlay = Overlay.NONE }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        val ink = if (focused) colors.pillInk else colors.ink
        Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(item.title.take(3).uppercase(), color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(item.title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GuideTile(colors: ThemeColors, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.width(150.dp).height(112.dp).clip(shape)
            .background(if (focused) colors.accent else colors.pill.copy(alpha = 0.2f))
            .border(if (focused) 2.dp else 1.dp, colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val ink = if (focused) colors.pill else colors.ink
        Text("Open full guide", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("Every channel in YouTube TV's Live tab", color = ink.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

/** Continue card: each show with artwork and progress; OK resumes it in its app. */
@Composable
fun ContinuePanel(state: HavenState, colors: ThemeColors) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { state.watchNow.tick(force = true) }
    Panel(colors, "Continue watching", "From apps that share progress with the TV · OK resumes", { state.overlay = Overlay.NONE }, width = 780) {
        ContinueList(state, colors, first)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun ContinueList(state: HavenState, colors: ThemeColors, first: FocusRequester?) {
    val items = state.watchNow.continueWatching
    if (items.isEmpty()) {
        Text("Nothing to continue. Apps that share progress with the TV show up here.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    items.forEachIndexed { i, item ->
        LaunchedEffect(item.art) { state.watchNow.loadArt(item.art) }
        val img = item.art?.let { state.watchNow.art[it] }
        GlassCard(colors, Modifier.fillMaxWidth(), focusRequester = if (i == 0) first else null, onClick = { state.watchNow.open(item); state.overlay = Overlay.NONE }) { ink ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(144.dp).height(81.dp).clip(RoundedCornerShape(10.dp)).background(colors.glass), contentAlignment = Alignment.Center) {
                    if (img != null) Image(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.title, color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(item.detail, state.apps[item.pkg]?.label.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, color = ink.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.progress?.let { p ->
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ink.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(colors.accent))
                        }
                    }
                }
                Text("Resume", color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
