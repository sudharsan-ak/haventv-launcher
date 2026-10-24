@file:OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class) // FlowRow for channel grids; focus exit for the details pane

package com.aktvhome.launcher.ui.watch

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
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

private enum class GameFilter(val label: String) { ALL("All games"), MINE("My team"), ALERTS("My alerts"), SCHEDULES("Schedules") }

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
 * The NBA panel, master and detail side by side. Left: the day's games (the main team's as a wide
 * card, the rest in two columns), filter chips (All games, My team, My alerts) and a date menu;
 * live cards refresh every 10 s in place. Right: the selected game's details.
 *
 * Focusing a card selects it after a short pause (so scrolling past cards fetches nothing). OK on
 * a card, or Right from the right-hand column, moves into the details; Left or Back there returns
 * to the card. Back on the left closes the panel.
 *
 * Schedules swaps the games for teams: your favorite teams, then either conference; the right side
 * shows the chosen team's season, and OK on one of its games opens that game here.
 */
@Composable
fun NbaPanel(state: HavenState, colors: ThemeColors) {
    val w = state.watchNow
    val s = state.settingsStore.settings
    val fav = s.favoriteTeam
    val mine = s.followedTeams + setOfNotNull(fav.ifBlank { null })
    val today = w.today()
    var day by remember { mutableStateOf(state.nbaGame?.second ?: today) }
    var filter by remember { mutableStateOf(GameFilter.ALL) }
    var choosingDay by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(state.nbaGame?.first) }
    var focusedCard by remember { mutableStateOf<String?>(null) }
    // Bumped to move focus into the details once their watch button exists.
    var toPane by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    val paneFocus = remember { FocusRequester() }
    val cards = remember { mutableMapOf<String, FocusRequester>() }
    fun cardFocus(id: String) = cards.getOrPut(id) { FocusRequester() }
    // Set until the selected card has been focused (on opening, or when asked to show a game).
    var focusPending by remember { mutableStateOf(true) }
    val inPane = remember { booleanArrayOf(false) }
    val focusManager = LocalFocusManager.current
    // Schedules: the team shown, and the card it was chosen from (a team can be in both lists).
    var team by remember { mutableStateOf(state.nbaSchedule ?: fav.ifBlank { null }) }
    var teamCard by remember { mutableStateOf<String?>(null) }
    var focusedTeam by remember { mutableStateOf<Pair<String, String>?>(null) }
    var west by remember { mutableStateOf(team?.let { it in Nba.WEST } ?: true) }
    /** The card focus returns to from the right side: the selected game's, or the team's in Schedules. */
    fun returnCard(): String? = if (filter == GameFilter.SCHEDULES) teamCard else selected
    fun backToCard() { returnCard()?.let { runCatching { cardFocus(it).requestFocus() } } }
    fun openPane(id: String) {
        selected = id
        toPane++
    }
    val close = { state.overlay = Overlay.NONE }
    LaunchedEffect(day) { w.loadDay(day) }
    val list = w.games[day]
    val anyLive = list?.any { it.state == GameState.LIVE } == true
    LaunchedEffect(day, anyLive) {
        while (anyLive) {
            delay(10_000)
            w.loadDay(day, force = true)
        }
    }
    fun ours(g: Game) = g.away.abbr in mine || g.home.abbr in mine
    val shown = when (filter) {
        GameFilter.ALL -> list.orEmpty()
        GameFilter.MINE -> list.orEmpty().filter(::ours)
        GameFilter.ALERTS -> list.orEmpty().filter { it.espnId in s.alertGames }
        GameFilter.SCHEDULES -> emptyList()
    }
    val main = shown.firstOrNull { it.involves(fav) }
    // Followed teams lead the grid (ringed); the rest keep ESPN's order (live first).
    val grid = shown.filter { it != main }.sortedByDescending { ours(it) }
    // Asked to show a game (from the Home card, the drawer, the schedule): its day, selected.
    LaunchedEffect(state.nbaGame) {
        val (id, d) = state.nbaGame ?: return@LaunchedEffect
        day = d
        selected = id
        filter = GameFilter.ALL
        focusPending = true
        state.nbaGame = null
    }
    // Otherwise the main team's game, else the first live one, else the first; focused on opening.
    LaunchedEffect(day, list == null, focusPending) {
        if (list == null) return@LaunchedEffect
        if (selected == null || list.none { it.espnId == selected }) {
            selected = (main ?: list.firstOrNull { it.state == GameState.LIVE } ?: list.firstOrNull())?.espnId
        }
        if (focusPending) {
            focusPending = false
            val id = selected
            // The card is composed in this frame; give it a moment before asking for focus.
            delay(50)
            runCatching { if (id != null) cardFocus(id).requestFocus() else first.requestFocus() }
        }
    }
    // Asked for a team's schedule (the "Full schedule" links): the Schedules tab on that team.
    LaunchedEffect(state.nbaSchedule) {
        val abbr = state.nbaSchedule ?: return@LaunchedEffect
        state.nbaSchedule = null
        filter = GameFilter.SCHEDULES
        team = abbr
        west = abbr in Nba.WEST
        // Its card among the favorite teams if it's one, else in its conference.
        val cardKey = if (abbr in mine) "fav:$abbr" else "conf:$abbr"
        teamCard = cardKey
        delay(50)
        runCatching { cardFocus(cardKey).requestFocus() }
    }
    // A team card in focus becomes the shown team after 300 ms, like the game cards.
    LaunchedEffect(focusedTeam) {
        val (cardKey, abbr) = focusedTeam ?: return@LaunchedEffect
        delay(300)
        team = abbr
        teamCard = cardKey
    }
    fun openTeam(key: String, abbr: String) {
        team = abbr
        teamCard = key
        toPane++
    }
    // A card in focus becomes the selection after 300 ms; moving on sooner cancels it.
    LaunchedEffect(focusedCard) {
        val id = focusedCard ?: return@LaunchedEffect
        delay(300)
        selected = id
    }
    LaunchedEffect(toPane) {
        if (toPane == 0) return@LaunchedEffect
        // The details may still be loading; try for a moment until the watch button is there.
        repeat(40) {
            if (runCatching { paneFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    // Frosted like the settings panels, over a home dimmed a bit more.
    HavenOverlay(dim = 0.35f, onDismiss = close, blur = 30) {
        Row(
            Modifier.align(Alignment.Center).width(860.dp).fillMaxHeight(0.88f)
                .glassPanel(colors, RoundedCornerShape(26.dp)).padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetImage(w, Nba.LEAGUE_LOGO, 30.dp)
                    Column {
                        Eyebrow("NBA · ${dayWord(day, today)}", colors)
                        Text(day.format(dateLineFmt), color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GameFilter.entries.forEachIndexed { i, f ->
                        TabChip(f.label, f == filter, colors, if (i == 0) Modifier.focusRequester(first) else Modifier) { filter = f }
                    }
                    Spacer(Modifier.weight(1f))
                    // A day only means something for games.
                    if (filter != GameFilter.SCHEDULES) TabChip("${dayWord(day, today)} ▾", false, colors) { choosingDay = true }
                }
                when {
                    filter == GameFilter.SCHEDULES -> TeamPicker(
                        state, colors, mine, fav, team, west,
                        onConference = { west = it },
                        focus = ::cardFocus,
                        onFocused = { key, abbr -> focusedTeam = key to abbr },
                        onOpen = ::openTeam,
                    )
                    list == null && w.nbaError -> Text("Couldn't reach ESPN. Check the connection and try again.", color = colors.inkSoft, fontSize = 12.sp)
                    list == null -> Text("Loading games…", color = colors.inkSoft, fontSize = 12.sp)
                    shown.isEmpty() -> Text(
                        when (filter) {
                            GameFilter.ALL -> "No NBA games on ${day.format(dateLineFmt)}."
                            GameFilter.MINE -> "Your teams don't play on ${day.format(dateLineFmt)}."
                            GameFilter.ALERTS -> "No games with Set alert on this day. Open a game and press Set alert."
                        },
                        color = colors.inkSoft, fontSize = 12.sp,
                    )
                    else -> {
                        if (main != null) {
                            SectionLabel("My team", colors)
                            key(main.espnId) {
                                MainGameCard(
                                    state, main, colors, chosen = main.espnId == selected, focus = cardFocus(main.espnId),
                                    modifier = Modifier.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT) { openPane(main.espnId) },
                                    onFocused = { focusedCard = main.espnId },
                                ) { openPane(main.espnId) }
                            }
                        }
                        if (grid.isNotEmpty()) {
                            SectionLabel("Other games", colors)
                            grid.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEachIndexed { col, g ->
                                        key(g.espnId) {
                                            // Right from the right-hand column goes into the details.
                                            val right = if (col == 1) Modifier.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT) { openPane(g.espnId) } else Modifier
                                            GameCard(
                                                state, g, colors, chosen = g.espnId == selected, followed = ours(g),
                                                modifier = Modifier.weight(1f).then(right), focus = cardFocus(g.espnId),
                                                onFocused = { focusedCard = g.espnId },
                                            ) { openPane(g.espnId) }
                                        }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                if (fav.isNotBlank() && filter != GameFilter.SCHEDULES) {
                    val next = w.nextFavorite()
                    GlassCard(colors, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp), onClick = { state.openSchedule(fav) }) { ink ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${Nba.teamName(fav)}" + (next?.let { " · Next: ${shortDate(it)} ${opponent(it, fav)}" } ?: ""),
                                color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text("Full schedule ›", color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // The selected game, in its own frosted card.
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier.weight(0.45f).fillMaxHeight().clip(shape).background(colors.glass).border(1.dp, colors.edge, shape)
                    .onFocusChanged { inPane[0] = it.hasFocus }
                    .onPreviewKeyEvent { e ->
                        when (e.nativeKeyEvent.keyCode) {
                            // Back in the details returns to the card instead of closing the panel.
                            KeyEvent.KEYCODE_BACK -> {
                                if (e.type == KeyEventType.KeyUp) backToCard()
                                true
                            }
                            else -> false
                        }
                    }
                    // Left moves normally within the details (Set alert to Watch, between tabs). Only
                    // when it would leave the details does it go back to the selected card, not
                    // whichever card happens to sit nearest.
                    .focusProperties {
                        exit = { dir ->
                            val id = returnCard()
                            if (dir == FocusDirection.Left && id != null) cardFocus(id) else FocusRequester.Default
                        }
                    }
                    .focusGroup()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                val id = selected
                val shownTeam = team
                if (filter == GameFilter.SCHEDULES && shownTeam != null) {
                    key(shownTeam) { TeamSchedulePane(state, colors, shownTeam, paneFocus, close) }
                } else if (filter != GameFilter.SCHEDULES && id != null) {
                    key(id) { GameDetailPane(state, colors, id, paneFocus, close) }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (filter == GameFilter.SCHEDULES) "Pick a team" else "No game selected",
                                color = colors.inkSoft, fontSize = 13.sp, modifier = Modifier.weight(1f),
                            )
                            CloseButton(colors, onClick = close)
                        }
                    }
                }
            }
        }
    }
    if (choosingDay) {
        DayMenu(colors, today, day, onPick = { day = it; choosingDay = false }, onPickDate = { choosingDay = false; picking = true }, onDismiss = { choosingDay = false })
    }
    if (picking) DatePickerDialog(colors, day, onPick = { day = it; picking = false }, onDismiss = { picking = false })
    // The recap's "show Summary" lasts as long as the panel.
    DisposableEffect(Unit) { onDispose { state.nbaSummaryFor = null } }
}

/**
 * The Schedules tab's left side: your favorite teams (main team first, three to a row), then
 * West / East and that conference's teams in two columns. Right from the end of a row goes into the
 * schedule, like OK.
 */
@Composable
private fun TeamPicker(
    state: HavenState,
    colors: ThemeColors,
    mine: Set<String>,
    main: String,
    shown: String?,
    west: Boolean,
    onConference: (Boolean) -> Unit,
    focus: (String) -> FocusRequester,
    onFocused: (key: String, abbr: String) -> Unit,
    onOpen: (key: String, abbr: String) -> Unit,
) {
    @Composable
    fun rows(prefix: String, teams: List<String>, perRow: Int, short: Boolean) {
        teams.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { col, abbr ->
                    val cardKey = "$prefix:$abbr"
                    val right = if (col == row.size - 1) Modifier.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT) { onOpen(cardKey, abbr) } else Modifier
                    key(cardKey) {
                        TeamPick(
                            state.watchNow, abbr, short, colors, chosen = abbr == shown, focus = focus(cardKey),
                            modifier = Modifier.weight(1f).then(right),
                            onFocused = { onFocused(cardKey, abbr) },
                        ) { onOpen(cardKey, abbr) }
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    val favorites = mine.sortedWith(compareBy({ it != main }, { Nba.teamName(it) }))
    if (favorites.isNotEmpty()) {
        SectionLabel("Favorite teams", colors)
        rows("fav", favorites, perRow = 3, short = true)
    }
    SectionLabel("Find any team", colors)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TabChip("West", west, colors) { onConference(true) }
        TabChip("East", !west, colors) { onConference(false) }
    }
    val conference = Nba.TEAMS.map { it.first }.filter { (it in Nba.WEST) == west }.sortedBy { Nba.teamName(it) }
    rows("conf", conference, perRow = 2, short = false)
}

/** A team to pick: its logo and name (the nickname among favorites, the full name in a conference). */
@Composable
private fun TeamPick(
    w: WatchNow, abbr: String, short: Boolean, colors: ThemeColors, chosen: Boolean, focus: FocusRequester,
    modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit,
) {
    GlassCard(
        colors, modifier, selected = chosen, focusRequester = focus, onFocused = onFocused,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), onClick = onClick,
    ) { ink ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NetImage(w, Nba.logo(abbr), 26.dp)
            val name = Nba.teamName(abbr)
            Text(
                if (short) name.substringAfterLast(' ').let { if (abbr == "POR") "Trail Blazers" else it } else name,
                color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val scheduleDateFmt = DateTimeFormatter.ofPattern("EEE MMM d")

/**
 * A team's season on the right side of Schedules: name and season, then every game, oldest first,
 * opened at the next one (ringed, and where focus lands). OK on a game opens it in the panel.
 */
@Composable
private fun TeamSchedulePane(state: HavenState, colors: ThemeColors, abbr: String, focus: FocusRequester, onClose: () -> Unit) {
    val w = state.watchNow
    LaunchedEffect(abbr) { w.loadSchedule(abbr) }
    val games = w.teamSchedules[abbr]
    val next = games?.let { all -> all.indexOfFirst { it.state != GameState.FINAL }.let { if (it < 0) all.size - 1 else it } } ?: 0
    val list = rememberLazyListState()
    LaunchedEffect(abbr, games != null) { if (games != null) list.scrollToItem((next - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NetImage(w, Nba.logo(abbr), 34.dp)
            Column(Modifier.weight(1f)) {
                Eyebrow("NBA · Schedule", colors)
                Text(Nba.teamName(abbr), color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                games?.firstOrNull()?.let { g0 ->
                    // A season starts in October: "2026–27 regular season".
                    val y = g0.start.atZone(ZoneId.systemDefault()).year
                    Text("$y–${(y + 1) % 100} regular season · ${games.size} games", color = colors.inkSoft, fontSize = 11.sp)
                }
            }
            CloseButton(colors, onClick = onClose)
        }
        when {
            games == null && w.teamScheduleFailed[abbr] == true -> Text("Couldn't reach ESPN for this schedule.", color = colors.inkSoft, fontSize = 12.sp)
            games == null -> Text("Loading schedule…", color = colors.inkSoft, fontSize = 12.sp)
            games.isEmpty() -> Text("No games scheduled.", color = colors.inkSoft, fontSize = 12.sp)
            else -> LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(games, key = { _, g -> g.espnId }) { i, g ->
                    ScheduleRow(g, abbr, colors, isNext = i == next, focus = if (i == next) focus else null) { state.openGame(g) }
                }
            }
        }
    }
}

/** Date, "@ LAL · Los Angeles Lakers" with the network or Final under it, and the result or tip-off time. */
@Composable
private fun ScheduleRow(g: Game, abbr: String, colors: ThemeColors, isNext: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    GlassCard(
        colors, Modifier.fillMaxWidth(), selected = isNext, focusRequester = focus,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp), onClick = onClick,
    ) { ink ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(g.start.atZone(ZoneId.systemDefault()).format(scheduleDateFmt), color = ink.copy(alpha = 0.75f), fontSize = 11.sp, modifier = Modifier.width(76.dp))
            Column(Modifier.weight(1f)) {
                val other = if (g.home.abbr == abbr) g.away.abbr else g.home.abbr
                Text("${opponent(g, abbr)} · ${Nba.teamName(other)}", color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (g.state) {
                    GameState.FINAL -> "Final"
                    GameState.LIVE -> "Live · ${g.clockLine()}"
                    GameState.PRE -> g.broadcasters.joinToString(" · ")
                }
                if (sub.isNotBlank()) Text(sub, color = ink.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                when (g.state) {
                    GameState.FINAL -> result(g, abbr)
                    GameState.LIVE -> "${g.away.score}–${g.home.score}"
                    GameState.PRE -> g.start.atZone(ZoneId.systemDefault()).format(tipFmt)
                },
                color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
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

/** The main team's game, full width: logos, abbreviations and records, with time or score between. */
@Composable
private fun MainGameCard(
    state: HavenState, g: Game, colors: ThemeColors, chosen: Boolean, focus: FocusRequester,
    modifier: Modifier = Modifier, onFocused: () -> Unit, onClick: () -> Unit,
) {
    val w = state.watchNow
    GlassCard(
        colors, modifier.fillMaxWidth(), selected = chosen, focusRequester = focus, onFocused = onFocused,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), onClick = onClick,
    ) { ink ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NetImage(w, Nba.logo(g.away), 36.dp)
            TeamMark(g.away, ink, 15.sp, Alignment.Start)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CardCentre(state, g, ink, big = true) }
            TeamMark(g.home, ink, 15.sp, Alignment.End)
            NetImage(w, Nba.logo(g.home), 36.dp)
        }
    }
}

/** A game in the two-column grid: each side's logo, abbreviation and record around the centre. */
@Composable
private fun GameCard(
    state: HavenState, g: Game, colors: ThemeColors, chosen: Boolean, followed: Boolean, modifier: Modifier,
    focus: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit,
) {
    val w = state.watchNow
    GlassCard(
        colors, modifier, selected = chosen || followed, focusRequester = focus, onFocused = onFocused,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp), onClick = onClick,
    ) { ink ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NetImage(w, Nba.logo(g.away), 24.dp)
                TeamMark(g.away, ink, 11.sp, Alignment.CenterHorizontally)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CardCentre(state, g, ink, big = false) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NetImage(w, Nba.logo(g.home), 24.dp)
                TeamMark(g.home, ink, 11.sp, Alignment.CenterHorizontally)
            }
        }
    }
}

@Composable
private fun TeamMark(t: Team, ink: Color, size: TextUnit, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align) {
        Text(t.abbr, color = ink, fontSize = size, fontWeight = FontWeight.Bold)
        if (t.record.isNotBlank()) Text(t.record, color = ink.copy(alpha = 0.65f), fontSize = 9.sp)
    }
}

/** Tip-off time or score; the clock with a red dot, or "Final"; the network (with its app's icon). */
@Composable
private fun CardCentre(state: HavenState, g: Game, ink: Color, big: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        val main = if (g.state == GameState.PRE) g.start.atZone(ZoneId.systemDefault()).format(tipFmt) else "${g.away.score} – ${g.home.score}"
        Text(main, color = ink, fontSize = if (big) 20.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        when (g.state) {
            GameState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(LIVE_RED))
                Text(g.clockLine(), color = ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            GameState.FINAL -> Text("Final", color = ink.copy(alpha = 0.75f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            GameState.PRE -> Unit
        }
        g.broadcasters.firstOrNull()?.let { net ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val icon = g.broadcasters.firstNotNullOfOrNull { networkApp(it) }?.let { state.apps[it]?.icon }
                if (icon != null) Image(icon, null, Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)))
                Text(net, color = ink.copy(alpha = 0.7f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
            liveGames.forEach { g -> GameRow(g, state.settingsStore.settings.favoriteTeam, colors) { state.openGame(g) } }
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
