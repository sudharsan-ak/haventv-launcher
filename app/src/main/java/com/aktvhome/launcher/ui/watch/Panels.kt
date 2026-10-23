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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.LiveGroup
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.Team
import com.aktvhome.launcher.data.WatchNow
import com.aktvhome.launcher.data.networkApp
import com.aktvhome.launcher.data.TvItem
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.GlassDialog
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
import java.time.ZoneId
import kotlinx.coroutines.delay
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

private enum class GameFilter(val label: String) { ALL("All games"), MINE("My team"), ALERTS("My alerts") }

private val dateLineFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private val tipFmt = DateTimeFormatter.ofPattern("h:mm a")

/** "Today", "Yesterday", "Tomorrow", or "Sun, Oct 25". */
private fun dayWord(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> day.format(dateLineFmt)
}

/**
 * NBA card: a day's games, your teams' first. Filter chips (All games, My team, My alerts) and a
 * date pill; live rows refresh every 30 s in place, focus staying on its row. OK opens a game's
 * details.
 */
@Composable
fun NbaPanel(state: HavenState, colors: ThemeColors) {
    val w = state.watchNow
    val s = state.settingsStore.settings
    val fav = s.favoriteTeam
    val mine = s.followedTeams + setOfNotNull(fav.ifBlank { null })
    val today = w.today()
    var day by remember { mutableStateOf(today) }
    var filter by remember { mutableStateOf(GameFilter.ALL) }
    var choosingDay by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val close = { state.overlay = Overlay.NONE }
    LaunchedEffect(day) { w.loadDay(day) }
    val list = w.games[day]
    val anyLive = list?.any { it.state == GameState.LIVE } == true
    LaunchedEffect(day, anyLive) {
        while (anyLive) {
            delay(30_000)
            w.loadDay(day, force = true)
        }
    }
    HavenOverlay(dim = 0.45f, onDismiss = close) {
        Column(
            Modifier.align(Alignment.Center).width(820.dp).fillMaxHeight(0.88f)
                .glassPanel(colors, RoundedCornerShape(28.dp), alpha = 0.92f)
                .verticalScroll(rememberScrollState())
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NetImage(w, Nba.LEAGUE_LOGO, 34.dp)
                Column(Modifier.weight(1f)) {
                    Eyebrow("NBA · ${dayWord(day, today)}", colors)
                    Text(day.format(dateLineFmt), color = colors.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                CloseButton(colors, onClick = close)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameFilter.entries.forEachIndexed { i, f ->
                    Pill(f.label, colors, selected = f == filter, focusRequester = if (i == 0) first else null) { filter = f }
                }
                Spacer(Modifier.weight(1f))
                Pill("${dayWord(day, today)} ▾", colors) { choosingDay = true }
            }
            when {
                list == null && w.nbaError -> Text("Couldn't reach ESPN. Check the connection and try again.", color = colors.inkSoft, fontSize = 12.sp)
                list == null -> Text("Loading games…", color = colors.inkSoft, fontSize = 12.sp)
                else -> {
                    fun ours(g: Game) = g.away.abbr in mine || g.home.abbr in mine
                    val shown = when (filter) {
                        GameFilter.ALL -> list
                        GameFilter.MINE -> list.filter(::ours)
                        GameFilter.ALERTS -> list.filter { it.espnId in s.alertGames }
                    }
                    // The main team's game leads its section; the rest keep ESPN's order (live first).
                    val my = shown.filter(::ours).sortedByDescending { it.involves(fav) }
                    val other = shown.filter { !ours(it) }
                    if (shown.isEmpty()) {
                        val why = when (filter) {
                            GameFilter.ALL -> "No NBA games on ${day.format(dateLineFmt)}."
                            GameFilter.MINE -> "Your teams don't play on ${day.format(dateLineFmt)}."
                            GameFilter.ALERTS -> "No games with Set alert on this day. Open a game and press Set alert."
                        }
                        Text(why, color = colors.inkSoft, fontSize = 12.sp)
                    }
                    if (my.isNotEmpty()) {
                        SectionLabel("My team", colors)
                        my.forEach { g -> key(g.espnId) { ScoreRow(state, g, colors, highlighted = true) { state.detailGameId = g.espnId } } }
                    }
                    if (other.isNotEmpty()) {
                        SectionLabel("Other games", colors)
                        other.forEach { g -> key(g.espnId) { ScoreRow(state, g, colors, highlighted = false) { state.detailGameId = g.espnId } } }
                    }
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
    }
    if (choosingDay) {
        DayMenu(colors, today, day, onPick = { day = it; choosingDay = false }, onPickDate = { choosingDay = false; picking = true }, onDismiss = { choosingDay = false })
    }
    if (picking) DatePickerDialog(colors, day, onPick = { day = it; picking = false }, onDismiss = { picking = false })
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun SectionLabel(text: String, colors: ThemeColors) {
    Box(Modifier.padding(top = 4.dp)) { Eyebrow(text, colors) }
}

/** Yesterday, Today, Tomorrow, the next five days, and Pick a date… */
@Composable
private fun DayMenu(colors: ThemeColors, today: LocalDate, current: LocalDate, onPick: (LocalDate) -> Unit, onPickDate: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    val days = (-1L..6L).map { today.plusDays(it) }
    GlassDialog(colors, "Show games for", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d ->
                Pill(
                    dayWord(d, today), colors, selected = d == current, modifier = Modifier.fillMaxWidth(),
                    focusRequester = if (d == current || (current !in days && d == today)) focus else null,
                ) { onPick(d) }
            }
            Pill("Pick a date…", colors, modifier = Modifier.fillMaxWidth(), onClick = onPickDate)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * A game: away logo, name and record; in the middle the tip-off time, the live score and clock
 * (with a red dot) or the final, and the network with its app's icon; then the home side.
 */
@Composable
private fun ScoreRow(state: HavenState, g: Game, colors: ThemeColors, highlighted: Boolean, onClick: () -> Unit) {
    val w = state.watchNow
    GlassCard(colors, Modifier.fillMaxWidth(), selected = highlighted, onClick = onClick) { ink ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScoreSide(w, g.away, ink, Modifier.weight(1f), home = false)
            Column(Modifier.width(180.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (g.state) {
                    GameState.PRE -> Text(g.start.atZone(ZoneId.systemDefault()).format(tipFmt), color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    GameState.LIVE -> {
                        Text("${g.away.score} – ${g.home.score}", color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(LIVE_RED))
                            Text(g.clockLine(), color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    GameState.FINAL -> {
                        Text("${g.away.score} – ${g.home.score}", color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Final", color = ink.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                g.network?.let { net ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val icon = g.networks.firstNotNullOfOrNull { networkApp(it) }?.let { state.apps[it]?.icon }
                        if (icon != null) Image(icon, null, Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)))
                        Text(net, color = ink.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ScoreSide(w, g.home, ink, Modifier.weight(1f), home = true)
            Text("›", color = ink.copy(alpha = 0.7f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Logo, short name and record; mirrored for the home team so the logos sit on the outside. */
@Composable
private fun ScoreSide(w: WatchNow, team: Team, ink: Color, modifier: Modifier, home: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, if (home) Alignment.End else Alignment.Start)) {
        if (!home) NetImage(w, Nba.logo(team), 30.dp)
        Column(horizontalAlignment = if (home) Alignment.End else Alignment.Start) {
            Text(team.name, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (team.record.isNotBlank()) Text("(${team.record})", color = ink.copy(alpha = 0.65f), fontSize = 11.sp)
        }
        if (home) NetImage(w, Nba.logo(team), 30.dp)
    }
}

/** The red of every LIVE mark. */
val LIVE_RED = Color(0xFFE53935)

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
            liveGames.forEach { g -> GameRow(g, state.settingsStore.settings.favoriteTeam, colors) { state.detailGameId = g.espnId } }
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
