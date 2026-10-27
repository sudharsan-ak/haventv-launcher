package com.aktvhome.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.core.search.SearchDocument
import com.aktvhome.launcher.core.search.SearchMatcher
import com.aktvhome.launcher.data.AppEntry
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.TvInput
import com.aktvhome.launcher.data.TvItem
import com.aktvhome.launcher.ui.settings.CloseButton
import com.aktvhome.launcher.ui.settings.Eyebrow
import com.aktvhome.launcher.ui.settings.HavenOverlay
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.watch.NetImage
import kotlinx.coroutines.delay

private enum class SearchCategory(val label: String) {
    ALL("All"), APPS("Apps"), SETTINGS("Settings"), TV("TV"), SPORTS("Sports"),
}

private const val MAX_RESULTS = 15
private const val SMARTTUBE_PACKAGE = "org.smarttube.stable"
private const val SMARTTUBE_MIN_QUERY = 3

private sealed interface SearchTarget {
    data class App(val app: AppEntry) : SearchTarget
    data class Setting(val destination: SettingsDestination, val settingId: String) : SearchTarget
    data class Collection(val id: String) : SearchTarget
    data class Input(val input: TvInput) : SearchTarget
    data class Tv(val item: TvItem) : SearchTarget
    data class SmartTube(val query: String) : SearchTarget
    data class Team(val abbr: String) : SearchTarget
    data class NbaGame(val game: Game) : SearchTarget
}

private data class SearchItem(
    val document: SearchDocument,
    val category: SearchCategory,
    val target: SearchTarget,
    val app: AppEntry? = null,
    val imageUrl: String? = null,
    val collectionIcon: String? = null,
)

@Composable
fun SearchOverlay(state: HavenState, colors: ThemeColors) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(SearchCategory.ALL) }
    var selected by remember { mutableIntStateOf(0) }
    val close = { state.overlay = Overlay.NONE }
    val keyboard = LocalSoftwareKeyboardController.current
    val queryFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val tabFocus = remember { SearchCategory.entries.associateWith { FocusRequester() } }

    LaunchedEffect(Unit) {
        state.watchNow.tick(force = true)
        delay(120)
        runCatching { queryFocus.requestFocus() }
        keyboard?.show()
    }

    // The TV can publish the same program in Watch Next and a preview row.
    val allItems = searchItems(state).distinctBy { it.document.id }
    val categoryItems = allItems.filter { category == SearchCategory.ALL || it.category == category }
    val smartTube = query.trim().takeIf {
        category == SearchCategory.ALL && it.length >= SMARTTUBE_MIN_QUERY && SMARTTUBE_PACKAGE in state.apps
    }?.let { smartTubeItem(it, state.apps.getValue(SMARTTUBE_PACKAGE)) }
    val localLimit = MAX_RESULTS - if (smartTube == null) 0 else 1
    val localResults = if (query.isBlank()) {
        if (category == SearchCategory.ALL) suggestions(state, allItems) else categoryItems.take(MAX_RESULTS)
    } else {
        val byId = categoryItems.associateBy { it.document.id }
        SearchMatcher.ranked(categoryItems.map { it.document }, query, localLimit).mapNotNull { byId[it.id] }
    }
    val results = listOfNotNull(smartTube) + localResults
    val listState = rememberLazyListState()
    LaunchedEffect(selected, results.map { it.document.id }) {
        if (results.isEmpty()) {
            selected = 0
        } else {
            val safeSelected = selected.coerceIn(results.indices)
            if (selected != safeSelected) selected = safeSelected
            listState.animateScrollToItem(safeSelected)
        }
    }

    fun activate(item: SearchItem) {
        keyboard?.hide()
        when (val target = item.target) {
            is SearchTarget.App -> { state.overlay = Overlay.NONE; state.launch(target.app) }
            is SearchTarget.Setting -> state.openSettings(target.destination, target.settingId)
            is SearchTarget.Collection -> { state.selectedCollection = target.id; state.overlay = Overlay.NONE }
            is SearchTarget.Input -> { state.overlay = Overlay.NONE; state.switchTo(target.input) }
            is SearchTarget.Tv -> { state.overlay = Overlay.NONE; state.watchNow.open(target.item) }
            is SearchTarget.SmartTube -> { state.overlay = Overlay.NONE; state.searchSmartTube(target.query) }
            is SearchTarget.Team -> state.openSchedule(target.abbr)
            is SearchTarget.NbaGame -> state.openGame(target.game)
        }
    }

    HavenOverlay(dim = 0.18f, onDismiss = close) {
        Column(
            Modifier.align(Alignment.Center).width(880.dp).height(480.dp)
                .glassPanel(colors, RoundedCornerShape(28.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 22.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Haven / Universal search", colors)
                    Text("Find it. Open it.", color = colors.ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("Apps, settings, TV and sports. All in one place.", color = colors.inkSoft, fontSize = 11.sp)
                }
                CloseButton(colors, Modifier.focusRequester(closeFocus), close)
            }

            SearchField(
                query, colors, queryFocus,
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp)
                    .focusProperties { down = tabFocus.getValue(category) },
                onChange = { query = it; selected = 0 },
                onDown = {
                    keyboard?.hide()
                    runCatching { tabFocus.getValue(category).requestFocus() }
                },
                onSubmit = { results.getOrNull(selected)?.let(::activate) },
            )

            Row(
                Modifier.padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchCategory.entries.forEach { tab ->
                    SearchTab(
                        tab.label, tab == category, colors,
                        Modifier.focusRequester(tabFocus.getValue(tab)).focusProperties {
                            up = queryFocus
                        },
                    ) {
                        category = tab
                        selected = 0
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.edge))

            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.58f).fillMaxHeight().padding(start = 28.dp, end = 14.dp, top = 12.dp, bottom = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(if (query.isBlank()) "Suggested" else "Search results", colors)
                        Spacer(Modifier.weight(1f))
                        Text(resultCountLabel(query, results.size), color = colors.inkSoft, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (results.isEmpty()) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No matches found", color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Try an app, setting, channel, team or HDMI input.", color = colors.inkSoft, fontSize = 11.sp)
                        }
                    } else {
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            itemsIndexed(results, key = { _, item -> item.document.id }) { index, item ->
                                SearchResultRow(
                                    item, state, colors, index == selected,
                                    Modifier,
                                    onFocused = { selected = index },
                                    onClick = { activate(item) },
                                )
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.edge))
                SearchDetail(
                    results.getOrNull(selected), state, colors,
                    Modifier.weight(0.42f).fillMaxHeight().padding(22.dp),
                    onOpen = { results.getOrNull(selected)?.let(::activate) },
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    colors: ThemeColors,
    focus: FocusRequester,
    modifier: Modifier,
    onChange: (String) -> Unit,
    onDown: () -> Unit,
    onSubmit: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier.focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { onSubmit(); true }
                    else -> false
                }
            }
            .clip(RoundedCornerShape(17.dp))
            .background(colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, RoundedCornerShape(17.dp))
            .padding(horizontal = 17.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = colors.inkSoft, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isBlank()) Text("Search Netflix, picture mode, Warriors, ESPN, HDMI…", color = colors.inkSoft, fontSize = 15.sp)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun SearchTab(label: String, selected: Boolean, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(50))
            .background(if (selected || focused) colors.glass else Color.Transparent)
            .border(if (selected || focused) 1.dp else 0.dp, if (focused) colors.accent else colors.edge, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = colors.ink, fontSize = 12.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun SearchResultRow(
    item: SearchItem,
    state: HavenState,
    colors: ThemeColors,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val active = focused || selected
    val ink = if (focused) colors.pillInk else colors.ink
    Row(
        modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(15.dp))
            .background(if (focused) colors.pill else if (selected) colors.glass else Color.Transparent)
            .border(if (active) 1.dp else 0.dp, if (focused) colors.accent else colors.edge, RoundedCornerShape(15.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchArtwork(item, state, colors, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.document.name, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.document.subtitle, color = ink.copy(alpha = 0.68f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = ink.copy(alpha = 0.65f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SearchDetail(
    item: SearchItem?,
    state: HavenState,
    colors: ThemeColors,
    modifier: Modifier,
    onOpen: () -> Unit,
) {
    if (item == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Start typing to search Haven.", color = colors.inkSoft, fontSize = 12.sp)
        }
        return
    }
    Column(modifier) {
        Eyebrow("Selected result", colors)
        Spacer(Modifier.height(14.dp))
        SearchArtwork(item, state, colors, 64)
        Spacer(Modifier.height(12.dp))
        Text(item.document.name, color = colors.ink, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.document.description, color = colors.inkSoft, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(colors.glass).padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OPENS IN", color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.width(9.dp))
            Text(destinationLabel(item, state), color = colors.ink, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        DetailAction(actionLabel(item, state), colors, Modifier.fillMaxWidth(), onOpen)
    }
}

@Composable
private fun DetailAction(label: String, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) colors.pillInk else colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchArtwork(item: SearchItem, state: HavenState, colors: ThemeColors, pixels: Int) {
    val size = pixels.dp
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(colors.glass).border(1.dp, colors.edge, RoundedCornerShape(size * 0.26f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            item.app != null -> Image(item.app.icon, item.app.label, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
            item.imageUrl != null -> NetImage(state.watchNow, item.imageUrl, size - 8.dp)
            item.collectionIcon != null -> Icon(CollectionIcons.of(item.collectionIcon), null, tint = colors.ink, modifier = Modifier.size(size * 0.58f))
            else -> Icon(iconFor(item.category, item.target), null, tint = colors.ink, modifier = Modifier.size(size * 0.55f))
        }
    }
}

private fun iconFor(category: SearchCategory, target: SearchTarget): ImageVector = when {
    target is SearchTarget.Input -> Icons.Rounded.SettingsInputHdmi
    target is SearchTarget.Collection -> Icons.Rounded.Apps
    target is SearchTarget.SmartTube -> Icons.Rounded.Search
    category == SearchCategory.APPS -> Icons.Rounded.Apps
    category == SearchCategory.SETTINGS -> Icons.Rounded.Settings
    category == SearchCategory.SPORTS -> Icons.Rounded.SportsBasketball
    target is SearchTarget.Tv -> Icons.Rounded.LiveTv
    else -> Icons.Rounded.Tv
}

private fun actionLabel(item: SearchItem, state: HavenState): String = when (val target = item.target) {
    is SearchTarget.App -> "Launch ${item.document.name}"
    is SearchTarget.Setting -> "Open in Settings"
    is SearchTarget.Collection -> "Open collection"
    is SearchTarget.Input -> "Switch to ${item.document.name}"
    is SearchTarget.Tv -> "Open in ${state.apps[target.item.pkg]?.label ?: "TV app"}"
    is SearchTarget.SmartTube -> "Search in SmartTube"
    is SearchTarget.Team -> "Open team schedule"
    is SearchTarget.NbaGame -> "Open game"
}

private fun destinationLabel(item: SearchItem, state: HavenState): String = when (val target = item.target) {
    is SearchTarget.App -> target.app.label
    is SearchTarget.Setting -> "Haven Settings"
    is SearchTarget.Collection -> "Haven Home"
    is SearchTarget.Input -> "TV input"
    is SearchTarget.Tv -> state.apps[target.item.pkg]?.label ?: "TV app"
    is SearchTarget.SmartTube -> "SmartTube · YouTube results"
    is SearchTarget.Team, is SearchTarget.NbaGame -> "Haven NBA"
}

private fun resultCountLabel(query: String, count: Int): String = when {
    query.isBlank() -> "$count suggestions"
    count == MAX_RESULTS -> "Top $MAX_RESULTS results"
    else -> "$count results"
}

private fun smartTubeItem(query: String, app: AppEntry) = SearchItem(
    SearchDocument(
        "smarttube:$query", "Search YouTube for “$query”", "YouTube results · SmartTube",
        "Open current YouTube results for “$query” in SmartTube.", "YouTube video search SmartTube",
    ),
    SearchCategory.TV, SearchTarget.SmartTube(query), app = app,
)

private fun searchItems(state: HavenState): List<SearchItem> = buildList {
    state.apps.values.sortedBy { it.label.lowercase() }.forEach { app ->
        val collection = state.layoutStore.collectionOf(app.pkg)?.name ?: "Installed app"
        add(
            SearchItem(
                SearchDocument("app:${app.pkg}", app.label, "$collection · Installed app", "Launch ${app.label} directly from Haven.", "$collection application"),
                SearchCategory.APPS, SearchTarget.App(app), app = app,
            ),
        )
    }
    HAVEN_SETTINGS.forEach { spec ->
        add(
            SearchItem(
                SearchDocument("setting:${spec.id}", spec.name, spec.path, spec.description, spec.keywords),
                SearchCategory.SETTINGS, SearchTarget.Setting(spec.destination, spec.id),
            ),
        )
    }
    state.shownCollections().forEach { shown ->
        add(
            SearchItem(
                SearchDocument(
                    "collection:${shown.collection.id}", shown.collection.name,
                    "Home collection · ${shown.apps.size} apps", "Open the ${shown.collection.name} collection on Haven Home.",
                    shown.apps.joinToString(" ") { it.label },
                ),
                SearchCategory.TV, SearchTarget.Collection(shown.collection.id), collectionIcon = shown.collection.icon,
            ),
        )
    }
    state.inputs.forEach { input ->
        add(
            SearchItem(
                SearchDocument("input:${input.id}", input.label, "TV input", "Switch to ${input.label}.", "HDMI source input"),
                SearchCategory.TV, SearchTarget.Input(input),
            ),
        )
    }
    state.watchNow.continueWatching.forEach { item ->
        add(tvItem(item, "Continue watching", "Resume ${item.title} in ${state.apps[item.pkg]?.label ?: item.pkg.substringAfterLast('.')}"))
    }
    (state.watchNow.live + state.watchNow.appRows).flatMap { group -> group.items.map { group.provider to it } }
        .distinctBy { it.second.pkg to it.second.title }
        .forEach { (provider, item) ->
            val kind = if (item.live) "Live now" else "TV channel or program"
            add(tvItem(item, "$provider · $kind", "Open ${item.title} in $provider"))
        }
    val favorite = state.settingsStore.settings.favoriteTeam
    Nba.TEAMS.sortedWith(compareBy<Pair<String, String>> { it.first != favorite }.thenBy { it.second }).forEach { (abbr, name) ->
        val nickname = name.substringAfterLast(' ')
        val aliases = buildString {
            append("NBA basketball $abbr $nickname team schedule")
            if (abbr == "GS") append(" GSW Golden State Warriors")
            if (abbr == favorite) append(" favorite team my team")
        }
        add(
            SearchItem(
                SearchDocument("team:$abbr", name, "NBA team", "Open the full $name schedule.", aliases),
                SearchCategory.SPORTS, SearchTarget.Team(abbr), imageUrl = Nba.logo(abbr),
            ),
        )
    }
    state.watchNow.games.values.flatten().distinctBy { it.espnId }.forEach { game ->
        val away = Nba.teamName(game.away.abbr)
        val home = Nba.teamName(game.home.abbr)
        val network = game.network?.let { " · $it" }.orEmpty()
        add(
            SearchItem(
                SearchDocument(
                    "game:${game.espnId}", "$away at $home", "${game.status}$network",
                    "Open this NBA game in Haven.", "$away $home ${game.away.abbr} ${game.home.abbr} NBA basketball ${game.network.orEmpty()}",
                ),
                SearchCategory.SPORTS, SearchTarget.NbaGame(game), imageUrl = Nba.logo(game.home),
            ),
        )
    }
}

private fun tvItem(item: TvItem, subtitle: String, description: String) = SearchItem(
    SearchDocument(
        "tv:${item.pkg}:${item.title}", item.title, subtitle, description,
        "${item.detail} ${item.pkg.substringAfterLast('.')} television live channel show",
    ),
    SearchCategory.TV, SearchTarget.Tv(item), imageUrl = item.art,
)

private fun suggestions(state: HavenState, all: List<SearchItem>): List<SearchItem> {
    val favorite = state.settingsStore.settings.favoriteTeam
    val wanted = listOfNotNull(
        all.firstOrNull { it.category == SearchCategory.APPS },
        all.firstOrNull { it.document.id == "setting:theme_kingdom" },
        all.firstOrNull { it.document.id == "setting:alerts_your_team" },
        all.firstOrNull { it.document.id == "setting:private_dns" },
        all.firstOrNull { it.document.id == "team:$favorite" },
        all.firstOrNull { it.target is SearchTarget.Tv },
        all.firstOrNull { it.target is SearchTarget.Input },
        all.firstOrNull { it.target is SearchTarget.Collection },
    )
    return wanted.distinctBy { it.document.id }.ifEmpty { all.take(8) }
}
