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
import com.aktvhome.launcher.data.AppEntry
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.SearchDocument
import com.aktvhome.launcher.data.SearchMatcher
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

private sealed interface SearchTarget {
    data class App(val app: AppEntry) : SearchTarget
    data class Setting(val destination: SettingsDestination) : SearchTarget
    data class Collection(val id: String) : SearchTarget
    data class Input(val input: TvInput) : SearchTarget
    data class Tv(val item: TvItem) : SearchTarget
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

private data class SettingSpec(
    val name: String,
    val destination: SettingsDestination,
    val path: String,
    val description: String,
    val keywords: String = "",
)

private val SETTINGS = listOf(
    SettingSpec("Theme", SettingsDestination.STYLE, "Appearance · Theme & motion", "Choose between Kingdom and Tide.", "look visual color skin"),
    SettingSpec("Day & night", SettingsDestination.STYLE, "Appearance · Theme & motion", "Choose automatic, day or night appearance.", "sunset sunrise auto lighting"),
    SettingSpec("Motion", SettingsDestination.STYLE, "Appearance · Theme & motion", "Control Haven's waves, glow and animations.", "animation reduced off"),
    SettingSpec("Wallpaper", SettingsDestination.WALLPAPER, "Appearance · Wallpaper", "Choose photo sources and wallpaper behavior.", "background Bing Earth View Wikimedia Unsplash"),
    SettingSpec("Change every", SettingsDestination.WALLPAPER, "Appearance · Wallpaper", "Choose how often photo wallpaper changes.", "interval hours"),
    SettingSpec("Unsplash topics", SettingsDestination.WALLPAPER, "Appearance · Wallpaper", "Choose the topics used for Unsplash photos.", "photos multi select"),
    SettingSpec("Unsplash key", SettingsDestination.WALLPAPER, "Appearance · Wallpaper", "Set the access key used for Unsplash.", "API access"),
    SettingSpec("Photo credit", SettingsDestination.WALLPAPER, "Appearance · Wallpaper", "Show or hide wallpaper attribution.", "photographer attribution"),
    SettingSpec("Card size", SettingsDestination.DISPLAY, "Home & layout · Display", "Adjust collection and app card size.", "small medium large tiles"),
    SettingSpec("Clock format", SettingsDestination.DISPLAY, "Home & layout · Display", "Choose 12-hour or 24-hour time.", "time 24 hour"),
    SettingSpec("Show greeting", SettingsDestination.DISPLAY, "Home & layout · Display", "Show or hide the welcome message.", "welcome home"),
    SettingSpec("Changing headline", SettingsDestination.DISPLAY, "Home & layout · Display", "Control the changing Home headline.", "headline greeting"),
    SettingSpec("Your name", SettingsDestination.DISPLAY, "Home & layout · Display", "Change the name used in the greeting.", "profile greeting"),
    SettingSpec("Collections", SettingsDestination.COLLECTIONS, "Home & layout · Collections", "Reorder, rename and organize app collections.", "manage move apps rows"),
    SettingSpec("Hidden apps", SettingsDestination.HIDDEN, "Home & layout · Hidden apps", "Choose which installed apps appear on Home.", "hide visibility"),
    SettingSpec("NBA alerts", SettingsDestination.NBA_ALERTS, "Watching & alerts · NBA alerts", "Choose teams, alert types and score visibility.", "basketball notifications close game overtime"),
    SettingSpec("Teams you follow", SettingsDestination.NBA_ALERTS, "Watching & alerts · NBA alerts", "Choose the NBA teams whose games you follow.", "basketball favorite logos"),
    SettingSpec("Hide scores", SettingsDestination.NBA_ALERTS, "Watching & alerts · NBA alerts", "Hide scores until games are opened.", "spoilers"),
    SettingSpec("Last night recap", SettingsDestination.NBA_ALERTS, "Watching & alerts · NBA alerts", "Control the morning NBA recap.", "yesterday results"),
    SettingSpec("Watch Now", SettingsDestination.WATCH_NOW, "Watching & alerts · Watch Now", "Configure NBA, Continue watching and Live channels.", "home strip TV"),
    SettingSpec("Favorite team", SettingsDestination.WATCH_NOW, "Watching & alerts · Watch Now", "Choose the NBA team shown first.", "basketball Warriors"),
    SettingSpec("Continue watching", SettingsDestination.WATCH_NOW, "Watching & alerts · Watch Now", "Show programs shared by installed TV apps.", "resume progress"),
    SettingSpec("Live channels", SettingsDestination.WATCH_NOW, "Watching & alerts · Watch Now", "Show channels shared by Live TV apps.", "television listings"),
    SettingSpec("Home button", SettingsDestination.HOME_BUTTON, "TV & network · Home button", "Choose whether the remote Home button opens Haven.", "launcher accessibility remote"),
    SettingSpec("Private DNS", SettingsDestination.DNS, "TV & network · Private DNS", "Configure encrypted DNS and enforcement.", "AdGuard Cloudflare Quad9 network privacy"),
    SettingSpec("Picture controls", SettingsDestination.TV_CONTROLS, "TV & network · TV controls", "Open picture settings supported by this TV.", "brightness display screen color"),
    SettingSpec("Sound output", SettingsDestination.TV_CONTROLS, "TV & network · TV controls", "Choose speakers and adjust supported sound controls.", "volume mute audio"),
    SettingSpec("Power saving", SettingsDestination.TV_CONTROLS, "TV & network · TV controls", "Configure supported TV power saving.", "energy eco"),
    SettingSpec("Sleep timer", SettingsDestination.TV_CONTROLS, "TV & network · TV controls", "Turn the TV off after a chosen duration.", "power minutes hour"),
    SettingSpec("Wi-Fi & Bluetooth", SettingsDestination.TV_CONTROLS, "TV & network · TV controls", "Check connectivity and open wireless settings.", "internet network devices"),
    SettingSpec("This TV", SettingsDestination.THIS_TV, "About & data · This TV", "Review this TV's available Haven capabilities.", "setup permissions device"),
    SettingSpec("Backup & reset", SettingsDestination.BACKUP, "About & data · Backup & reset", "Back up, restore or reset Haven.", "data export import"),
    SettingSpec("About Haven", SettingsDestination.ABOUT, "About & data · About Haven", "View Haven version and package information.", "version Android TV"),
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

    val allItems = searchItems(state)
    val categoryItems = allItems.filter { category == SearchCategory.ALL || it.category == category }
    val results = if (query.isBlank()) {
        if (category == SearchCategory.ALL) suggestions(state, allItems) else categoryItems
    } else {
        val byId = categoryItems.associateBy { it.document.id }
        SearchMatcher.ranked(categoryItems.map { it.document }, query).mapNotNull { byId[it.id] }
    }
    if (selected !in results.indices) selected = 0
    val resultFocus = remember(results.map { it.document.id }) { results.map { FocusRequester() } }
    val detailFocus = remember(results.getOrNull(selected)?.document?.id) { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(selected, results.size) {
        if (results.isNotEmpty()) listState.animateScrollToItem(selected)
    }

    fun activate(item: SearchItem) {
        keyboard?.hide()
        when (val target = item.target) {
            is SearchTarget.App -> { state.overlay = Overlay.NONE; state.launch(target.app) }
            is SearchTarget.Setting -> state.openSettings(target.destination)
            is SearchTarget.Collection -> { state.selectedCollection = target.id; state.overlay = Overlay.NONE }
            is SearchTarget.Input -> { state.overlay = Overlay.NONE; state.switchTo(target.input) }
            is SearchTarget.Tv -> { state.overlay = Overlay.NONE; state.watchNow.open(target.item) }
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
                            if (results.isNotEmpty()) down = resultFocus.first()
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
                        Text("${results.size} results", color = colors.inkSoft, fontSize = 10.sp)
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
                                    Modifier.focusRequester(resultFocus[index]).focusProperties {
                                        up = if (index == 0) tabFocus.getValue(category) else resultFocus[index - 1]
                                        down = if (index == results.lastIndex) resultFocus[index] else resultFocus[index + 1]
                                        right = detailFocus
                                    },
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
                    detailFocus,
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
    actionFocus: FocusRequester,
    onOpen: () -> Unit,
) {
    if (item == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Start typing to search Haven.", color = colors.inkSoft, fontSize = 12.sp)
        }
        return
    }
    Column(modifier) {
        Eyebrow("${item.category.label} / Result", colors)
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
            Text("LOCATION", color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.width(9.dp))
            Text(item.document.subtitle, color = colors.ink, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        DetailAction(actionLabel(item), colors, Modifier.fillMaxWidth().focusRequester(actionFocus), onOpen)
    }
}

@Composable
private fun DetailAction(label: String, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(colors.pill)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        Text(label, color = colors.pillInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    category == SearchCategory.APPS -> Icons.Rounded.Apps
    category == SearchCategory.SETTINGS -> Icons.Rounded.Settings
    category == SearchCategory.SPORTS -> Icons.Rounded.SportsBasketball
    target is SearchTarget.Tv -> Icons.Rounded.LiveTv
    else -> Icons.Rounded.Tv
}

private fun actionLabel(item: SearchItem): String = when (item.target) {
    is SearchTarget.App -> "Launch ${item.document.name}"
    is SearchTarget.Setting -> "Open in Settings"
    is SearchTarget.Collection -> "Open collection"
    is SearchTarget.Input -> "Switch to ${item.document.name}"
    is SearchTarget.Tv -> "Open ${item.document.name}"
    is SearchTarget.Team -> "Open team schedule"
    is SearchTarget.NbaGame -> "Open game"
}

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
    SETTINGS.forEach { spec ->
        add(
            SearchItem(
                SearchDocument("setting:${spec.name}", spec.name, spec.path, spec.description, spec.keywords),
                SearchCategory.SETTINGS, SearchTarget.Setting(spec.destination),
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
        all.firstOrNull { it.document.id == "setting:Theme" },
        all.firstOrNull { it.document.id == "setting:NBA alerts" },
        all.firstOrNull { it.document.id == "setting:Private DNS" },
        all.firstOrNull { it.document.id == "team:$favorite" },
        all.firstOrNull { it.target is SearchTarget.Tv },
        all.firstOrNull { it.target is SearchTarget.Input },
        all.firstOrNull { it.target is SearchTarget.Collection },
    )
    return wanted.distinctBy { it.document.id }.ifEmpty { all.take(8) }
}
