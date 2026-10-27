@file:OptIn(ExperimentalLayoutApi::class) // FlowRow, for the two-column grids

package com.aktvhome.launcher.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.R
import com.aktvhome.launcher.data.AppCollection
import com.aktvhome.launcher.data.AppEntry
import com.aktvhome.launcher.data.Background
import com.aktvhome.launcher.data.WALL_TOPICS
import com.aktvhome.launcher.data.WallInterval
import com.aktvhome.launcher.data.WallSource
import com.aktvhome.launcher.data.CardSize
import com.aktvhome.launcher.data.DayMode
import com.aktvhome.launcher.data.Defaults
import com.aktvhome.launcher.data.DnsChoice
import com.aktvhome.launcher.data.HomeOverride
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.WATCH_SECTIONS
import com.aktvhome.launcher.data.LOCAL_NETWORKS
import com.aktvhome.launcher.data.PrivateDns
import com.aktvhome.launcher.data.Motion
import com.aktvhome.launcher.data.ThemeId
import com.aktvhome.launcher.ui.CollectionIcons
import com.aktvhome.launcher.ui.CollectionPickerDialog
import com.aktvhome.launcher.ui.GlassDialog
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.IconBadge
import com.aktvhome.launcher.ui.LocalTvInteractionsEnabled
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.SettingsDestination
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.TextInputDialog
import com.aktvhome.launcher.ui.kingdom.KingdomHome
import com.aktvhome.launcher.ui.theme.Palettes
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tide.TideHome
import com.aktvhome.launcher.ui.tvClickable
import com.aktvhome.launcher.ui.watch.NetImage
import kotlinx.coroutines.delay

private enum class SettingsGroup(
    val label: String,
    val icon: ImageVector,
    val note: String,
    val subtitle: String,
) {
    APPEARANCE(
        "Appearance", Icons.Rounded.Palette, "Theme, motion, wallpaper",
        "Choose how Haven looks and feels.",
    ),
    HOME(
        "Home & layout", Icons.Rounded.ViewModule, "Cards, greeting, collections",
        "Organize your home and control what appears.",
    ),
    WATCHING(
        "Watching & alerts", Icons.Rounded.Schedule, "NBA, Watch Now, live TV",
        "Stay close to games and pick up where you left off.",
    ),
    SYSTEM(
        "TV & network", Icons.Rounded.Home, "Home button, DNS, TV controls, cleanup",
        "Connect Haven with the rest of your TV.",
    ),
    ABOUT(
        "About & data", Icons.Rounded.Info, "Version and app information",
        "See app information and future data tools.",
    ),
}

/** Phase 2 pages stay defined here but do not appear until their feature is implemented. */
private enum class Page(val label: String, val group: SettingsGroup, val available: Boolean = true) {
    STYLE("Theme & motion", SettingsGroup.APPEARANCE),
    WALLPAPER("Wallpaper", SettingsGroup.APPEARANCE),
    DISPLAY("Display", SettingsGroup.HOME),
    COLLECTIONS("Collections", SettingsGroup.HOME),
    HIDDEN("Hidden apps", SettingsGroup.HOME),
    NBA_ALERTS("NBA alerts", SettingsGroup.WATCHING),
    WATCH_NOW("Watch Now", SettingsGroup.WATCHING),
    LIVE_CHANNELS("Live channels", SettingsGroup.WATCHING, available = false),
    HOME_BUTTON("Home button", SettingsGroup.SYSTEM),
    DNS("Private DNS", SettingsGroup.SYSTEM),
    TV_CONTROLS("TV controls", SettingsGroup.SYSTEM),
    CLEANUP("Cleanup", SettingsGroup.SYSTEM),
    THIS_TV("This TV", SettingsGroup.ABOUT),
    BACKUP("Backup & reset", SettingsGroup.ABOUT),
    ABOUT("About Haven", SettingsGroup.ABOUT),
}

private fun SettingsGroup.pages(): List<Page> = Page.entries.filter { it.group == this && it.available }

private val visibleGroups = SettingsGroup.entries.filter { it.pages().isNotEmpty() }

/**
 * All settings, in a glass panel floating over the live home screen. Every change applies
 * immediately, so the home behind (and the theme previews) show the result as you go.
 */
@Composable
fun Personalize(state: HavenState, colors: ThemeColors) {
    val store = state.settingsStore
    val s = store.settings
    val layout = state.layoutStore
    val close = { state.overlay = Overlay.NONE }

    val initialRequest = remember { state.settingsDestination }
    val initialPage = initialRequest?.let { request ->
        Page.entries.firstOrNull { it.name == request.destination.name }
    } ?: Page.STYLE
    var group by remember { mutableStateOf(initialPage.group) }
    var page by remember { mutableStateOf(initialPage) }
    var contentFocused by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    val navFocus = remember { visibleGroups.associateWith { FocusRequester() } }
    // Right/OK from the menu lands on the selected tab. Content has its own first target.
    val contentFirst = remember { FocusRequester() }
    val pageFirst = remember { FocusRequester() }
    val settingFocus = remember { FocusRequester() }
    val settingTarget = initialRequest?.settingId?.let { SettingSearchTarget(it, settingFocus) }
    var searchFocusPending by remember { mutableStateOf(initialRequest?.settingId != null) }
    val first = Modifier.focusRequester(pageFirst)
    val contentScroll = rememberScrollState()

    LaunchedEffect(Unit) { state.settingsDestination = null }

    LaunchedEffect(initialRequest?.settingId) {
        if (initialRequest?.settingId == null) {
            searchFocusPending = false
            return@LaunchedEffect
        }
        repeat(20) {
            delay(if (it == 0) 180 else 250)
            if (runCatching { settingFocus.requestFocus() }.isSuccess) {
                searchFocusPending = false
                return@LaunchedEffect
            }
        }
        searchFocusPending = false
    }

    LaunchedEffect(page, openId) { contentScroll.scrollTo(0) }

    // Named focus targets for things that move (collections and their arrows), so focus can follow them.
    val targets = remember { mutableMapOf<String, FocusRequester>() }
    fun target(key: String) = targets.getOrPut(key) { FocusRequester() }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    // When the page's content swaps out (opening a collection, hiding an app), the focused item
    // vanishes and the window would hand focus to the first menu item, flipping to the Theme page.
    // So focus is parked on the close button first, then [pendingFocus] moves it where it belongs.
    val closeFocus = remember { FocusRequester() }
    fun park() = runCatching { closeFocus.requestFocus() }
    LaunchedEffect(pendingFocus, layout.layout) {
        val k = pendingFocus ?: return@LaunchedEffect
        runCatching { if (k == FIRST) pageFirst.requestFocus() else target(k).requestFocus() }
        pendingFocus = null
    }

    // Dialog state.
    var editingName by remember { mutableStateOf(false) }
    var editingDns by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }
    var pickingTeam by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var pickingStation by remember { mutableStateOf<String?>(null) }
    var collectionMenu by remember { mutableStateOf<AppCollection?>(null) }
    var renaming by remember { mutableStateOf<AppCollection?>(null) }
    var iconFor by remember { mutableStateOf<AppCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf<AppEntry?>(null) }
    var pickerFor by remember { mutableStateOf<AppEntry?>(null) }

    // Back arrives here as the overlay window's dismiss request (a BackHandler never sees it),
    // so it steps out one level at a time: collection, then page, then the panel itself.
    val onBack: () -> Unit = {
        val open = openId
        when {
            contentFocused && open != null -> {
                park()
                openId = null
                pendingFocus = "col:$open"
            }
            contentFocused -> { runCatching { navFocus.getValue(group).requestFocus() } }
            else -> close()
        }
    }

    val cols = layout.layout.collections
    val opened = cols.firstOrNull { it.id == openId }
    fun countOf(c: AppCollection) = c.apps.count { it in state.apps && it !in layout.layout.hidden }

    HavenOverlay(dim = 0.18f, onDismiss = onBack) {
        val panelShape = RoundedCornerShape(28.dp)
        Row(
            Modifier
                .align(Alignment.Center)
                .width(860.dp)
                .height(468.dp)
                .glassPanel(colors, panelShape),
        ) {
            // Menu.
            Column(
                Modifier.width(232.dp).fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.025f))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 14.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Eyebrow("Haven · Settings", colors)
                Text("Personalize", color = colors.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Everything in its place. A cleaner way to make Haven yours.", color = colors.inkSoft, fontSize = 10.sp)
                Spacer(Modifier.height(14.dp))
                visibleGroups.forEach { g ->
                    NavItem(
                        g, colors, selected = g == group,
                        modifier = Modifier
                            .focusRequester(navFocus.getValue(g))
                            .focusProperties { right = contentFirst },
                        onFocused = {
                            if (!searchFocusPending && g != group) {
                                group = g
                                page = g.pages().first()
                                openId = null
                            }
                        },
                        onClick = { runCatching { contentFirst.requestFocus() } },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.edge))
                Spacer(Modifier.height(10.dp))
                Text("Quick Settings stays as it is.", color = colors.ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Use the gear for instant changes.", color = colors.inkSoft, fontSize = 9.sp)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.ink.copy(alpha = 0.14f)))

            // Page.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { contentFocused = it.hasFocus }
                    .padding(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(if (page == Page.COLLECTIONS && opened != null) "Home & layout / Collections / ${opened.name}" else "Haven / ${group.label}", colors)
                        Text(
                            if (page == Page.COLLECTIONS && opened != null) opened.name else group.label,
                            color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (page == Page.COLLECTIONS && opened != null) openedSubtitle(opened, countOf(opened), state.inputs.size)
                            else group.subtitle,
                            color = colors.inkSoft, fontSize = 11.sp,
                        )
                    }
                    CloseButton(colors, Modifier.focusRequester(closeFocus), onClick = close)
                }
                if (opened == null) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.pages().forEach { p ->
                            SettingsTab(
                                p.label, selected = p == page, colors = colors,
                                modifier = if (p == page) Modifier.focusRequester(contentFirst) else Modifier,
                                onFocused = { page = p },
                                onClick = { page = p },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.edge))
                }
                Spacer(Modifier.height(10.dp))
                CompositionLocalProvider(LocalSettingSearchTarget provides settingTarget) {
                    SettingsPickerHost(colors, Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(contentScroll),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            when (page) {
                        Page.STYLE -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ThemeId.entries.forEachIndexed { i, t ->
                                    ThemeCard(
                                        t, state, chosen = s.theme == t, colors = colors,
                                        modifier = Modifier.weight(1f).then(if (i == 0) first else Modifier),
                                    ) { store.update { it.copy(theme = t) } }
                                }
                            }
                            Eyebrow("Appearance controls", colors)
                            val dayMode = when (s.dayMode) {
                                DayMode.AUTO -> "Auto"
                                DayMode.DAY -> "Day"
                                DayMode.NIGHT -> "Night"
                            }
                            SettingsChoiceRow(
                                "Day & night", "Auto blends around 6 AM and 6 PM.", dayMode,
                                listOf("Auto", "Day", "Night").map { SettingsPickerOption(it) }, dayMode, colors,
                            ) { value ->
                                store.update {
                                    it.copy(dayMode = when (value) {
                                        "Day" -> DayMode.DAY
                                        "Night" -> DayMode.NIGHT
                                        else -> DayMode.AUTO
                                    })
                                }
                            }
                            SettingsChoiceRow(
                                "Motion", "Controls Haven's waves, glow and theme animations.", s.motion.label,
                                listOf("Full", "Reduced", "Off").map { SettingsPickerOption(it) }, s.motion.label, colors,
                            ) { value -> store.update { it.copy(motion = Motion.entries.first { motion -> motion.label == value }) } }
                        }
                        Page.DISPLAY -> {
                            SettingsChoiceRow(
                                "Card size", "Applies to Kingdom cards and Tide dock icons.", s.cardSize.label,
                                CardSize.entries.map { SettingsPickerOption(it.label) }, s.cardSize.label, colors,
                            ) { value -> store.update { it.copy(cardSize = CardSize.entries.first { size -> size.label == value }) } }
                            SettingsToggleRow("Clock format", "Choose the way time appears.", s.clock24, colors) {
                                store.update { it.copy(clock24 = !it.clock24) }
                            }
                            SettingsToggleRow("Show greeting", "Show or hide your welcome message.", s.showGreeting, colors) {
                                store.update { it.copy(showGreeting = !it.showGreeting) }
                            }
                            SettingsToggleRow("Headline", "Kingdom's big line: follows your team's game and the time of day, or stays fixed.", s.headlineChanging, colors) {
                                store.update { it.copy(headlineChanging = !it.headlineChanging) }
                            }
                            SettingsActionRow("Your name", "Used in the home screen greeting.", s.name.ifBlank { "Not set" }, colors) { editingName = true }
                            SettingsStatusRow("Preview", state.greetingLine() ?: "Greeting is off", colors)
                        }
                        Page.WALLPAPER -> {
                            val walls = state.wallpapers
                            SettingsToggleRow("Kingdom", "Background: ${s.bgKingdom.label}", s.bgKingdom == Background.PHOTO, colors) {
                                store.update { it.copy(bgKingdom = if (it.bgKingdom == Background.PHOTO) Background.SCENE else Background.PHOTO) }
                                walls.tick()
                            }
                            SettingsToggleRow("Tide", "Background: ${s.bgTide.label}", s.bgTide == Background.PHOTO, colors) {
                                store.update { it.copy(bgTide = if (it.bgTide == Background.PHOTO) Background.SCENE else Background.PHOTO) }
                                walls.tick()
                            }
                            SettingsActionRow("Next photo now", "Fetch a new one", "Next photo now", colors) { walls.tick(force = true) }
                            SettingsStatusRow(
                                "Wallpaper view",
                                "Press Back on the home screen to see just the wallpaper. Press any button to bring Haven back.",
                                colors,
                            )
                            Eyebrow("Sources", colors)
                            WallSource.entries.forEach { src ->
                                val on = src in s.wallSources
                                val sub = if (src == WallSource.UNSPLASH && s.unsplashKey.isBlank()) "Needs your key (below)" else src.note
                                SettingsToggleRow(src.label, sub, on, colors) {
                                    store.update { it.copy(wallSources = if (on) it.wallSources - src else it.wallSources + src) }
                                }
                            }
                            SettingsMultiChoiceRow(
                                "Unsplash topics", "", if (s.wallTopics.isEmpty()) "None" else "${s.wallTopics.size} selected",
                                WALL_TOPICS.map { (label, value) -> SettingsPickerOption(label, label, value) }, s.wallTopics, colors,
                            ) { label ->
                                val on = label in s.wallTopics
                                store.update { it.copy(wallTopics = if (on) it.wallTopics - label else it.wallTopics + label) }
                            }
                            SettingsChoiceRow(
                                "Change every", "", s.wallInterval.label,
                                WallInterval.entries.map { SettingsPickerOption(it.name, it.label) }, s.wallInterval.name, colors,
                            ) { value -> store.update { it.copy(wallInterval = WallInterval.valueOf(value)) } }
                            SettingsActionRow(
                                "Unsplash key",
                                if (s.unsplashKey.isBlank()) "Not set. OK to paste it" else "Set (ends in ${s.unsplashKey.takeLast(4)})",
                                if (s.unsplashKey.isBlank()) "Not set" else "••••••••", colors,
                            ) { editingKey = true }
                            SettingsToggleRow("Photo credit", if (s.wallCredit) "Shown in the corner" else "Hidden", s.wallCredit, colors) {
                                store.update { it.copy(wallCredit = !it.wallCredit) }
                            }
                            SettingsStatusRow(
                                "Now showing",
                                listOf(walls.credit, walls.status).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "No photo yet" },
                                colors,
                            )
                        }
                        Page.HOME_BUTTON -> {
                            val ctx = LocalContext.current
                            var on by remember { mutableStateOf(HomeOverride.isOn(ctx)) }
                            SettingsToggleRow(
                                "Open Haven with Home",
                                if (on) "On: Home opens Haven" else "Off: Home opens this TV's own home screen",
                                on, colors,
                            ) {
                                // Without the one-time grant Haven can't flip it; the TV's own
                                // Accessibility settings can, so open those instead.
                                if (HomeOverride.set(ctx, !on)) on = HomeOverride.isOn(ctx)
                                else openSystemPage(ctx, android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                            SettingsStatusRow(
                                "Other launcher overrides",
                                "If another launcher is set to override your current launcher, turn that option off in its settings so it doesn't conflict with Haven.",
                                colors,
                            )
                            SettingsStatusRow(
                                "What it does",
                                "Google TV always opens its own home first. This watches only for that page and opens Haven instead. Your other accessibility services are untouched.",
                                colors,
                            )
                        }
                        Page.TV_CONTROLS -> TvControlsPage(state, colors, first)
                        Page.CLEANUP -> CleanupPage(state, colors, first)
                        Page.DNS -> {
                            val dns by rememberDnsView(state)
                            if (!state.canSetDns()) {
                                SettingsStatusRow("One-time setup needed", "Use Set up in About & data › This TV, or run on a computer: ${PrivateDns.GRANT_COMMAND}", colors)
                            }
                            val activeDns = dns.active ?: DnsChoice.OFF
                            SettingsChoiceRow(
                                "Private DNS", dns.status.text, activeDns.label,
                                DnsChoice.entries.map { choice ->
                                    val sub = when {
                                        choice == DnsChoice.CUSTOM && s.dnsCustom.isNotBlank() -> s.dnsCustom
                                        else -> choice.host ?: choice.note
                                    }
                                    SettingsPickerOption(choice.name, choice.label, sub)
                                }, activeDns.name, colors,
                            ) { value ->
                                val choice = DnsChoice.valueOf(value)
                                if (choice == DnsChoice.CUSTOM) editingDns = true else state.selectDns(choice)
                            }
                            SettingsToggleRow(
                                "Keep enforced",
                                if (s.dnsEnforce) "On: restores ${s.dnsChoice.label} at startup and whenever Haven opens" else "Off",
                                s.dnsEnforce, colors,
                            ) { store.update { it.copy(dnsEnforce = !it.dnsEnforce) } }
                            SettingsStatusRow("Status", dns.status.text, colors)
                        }
                        Page.COLLECTIONS -> if (opened == null) {
                            CollectionsGrid(
                                cols, colors, first, ::target,
                                countLabel = { c ->
                                    when {
                                        c.isInputs && c.hidden -> "Hidden from home"
                                        c.isInputs -> "${state.inputs.size} inputs"
                                        else -> "${countOf(c)} apps"
                                    }
                                },
                                onOpen = { c -> park(); openId = c.id; pendingFocus = FIRST },
                                onMenu = { c -> collectionMenu = c },
                                onShift = { c, d, arrow -> layout.shiftCollection(c.id, d); pendingFocus = "$arrow:${c.id}" },
                                onNew = { creating = true },
                            )
                        } else if (opened.isInputs) {
                            SettingsToggleRow("Show on home", if (opened.hidden) "Hidden" else "Shown", !opened.hidden, colors) {
                                layout.setCollectionHidden(opened.id, !opened.hidden)
                            }
                            SettingsActionRow("Icon", "Change the row's icon", "Change icon", colors) { iconFor = opened }
                            SettingsStatusRow("Your inputs", state.inputs.joinToString(" · ") { it.label }.ifBlank { "None found" }, colors)
                        } else {
                            SettingsActionRow("Change icon", "", "Change icon", colors) { iconFor = opened }
                            SettingsActionRow("Rename", "", "Rename", colors) { renaming = opened }
                            val apps = opened.apps.filter { it !in layout.layout.hidden }.mapNotNull { state.apps[it] }
                            if (apps.isEmpty()) {
                                Text("Empty. Hold OK on any app on the home screen and choose \"Move to collection\".", color = colors.inkSoft, fontSize = 12.sp)
                            }
                            val visible = apps.map { it.pkg }
                            TwoColumns {
                                apps.forEachIndexed { i, app ->
                                    run {
                                        ArrowTile(
                                            title = app.label, subtitle = null, colors = colors,
                                            image = app.icon, icon = null,
                                            mainFocus = target("app:${app.pkg}"),
                                            upFocus = target("up:${app.pkg}"), downFocus = target("down:${app.pkg}"),
                                            canUp = i > 0, canDown = i < apps.lastIndex,
                                            modifier = Modifier.weight(1f),
                                            onOpen = { appMenu = app }, onMenu = { appMenu = app },
                                            onUp = { layout.shiftApp(app.pkg, -1, visible); pendingFocus = "up:${app.pkg}" },
                                            onDown = { layout.shiftApp(app.pkg, +1, visible); pendingFocus = "down:${app.pkg}" },
                                        )
                                    }
                                }
                            }
                        }
                        Page.HIDDEN -> {
                            // Snapshot on entry, so unhiding an app doesn't make the list jump under you.
                            val listed = remember(page) { state.hiddenApps() }
                            if (listed.isEmpty()) {
                                Text("Nothing hidden. Hold OK on an app and choose Hide.", color = colors.inkSoft, fontSize = 12.sp,
                                    modifier = first.then(Modifier.tvClickable { }))
                            }
                            listed.chunked(1).forEachIndexed { r, pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    pair.forEachIndexed { c, app ->
                                        val isHidden = app.pkg in layout.layout.hidden
                                        GlassCard(
                                            colors, Modifier.weight(1f).then(if (r == 0 && c == 0) first else Modifier),
                                            onClick = { if (isHidden) layout.unhide(app.pkg) else layout.hide(app.pkg) },
                                        ) { ink ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Image(app.icon, null, Modifier.size(28.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(app.label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(if (isHidden) "Hidden from home" else "Shown on home", color = ink.copy(alpha = 0.7f), fontSize = 10.sp)
                                                }
                                                Switch(isHidden, colors)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Page.ABOUT -> AboutCard(colors, first)
                        Page.THIS_TV -> ThisTvPage(state, colors, first)
                        Page.WATCH_NOW -> {
                            SettingsToggleRow("NBA", "Scores, the 7-day schedule and your team. Uses ESPN and nba.com.", s.watchNba, colors) {
                                store.update { it.copy(watchNba = !it.watchNba) }
                            }
                            SettingsChoiceRow(
                                "Favorite team", "Shown first on Home and in Watch Now, with its full schedule.",
                                if (s.favoriteTeam.isBlank()) "None" else Nba.teamName(s.favoriteTeam),
                                listOf(SettingsPickerOption("", "None")) + Nba.TEAMS.map { (abbr, name) ->
                                    SettingsPickerOption(
                                        abbr,
                                        name,
                                        leading = { NetImage(state.watchNow, Nba.logo(abbr), 24.dp) },
                                    )
                                },
                                s.favoriteTeam, colors,
                            ) { abbr -> store.update { it.copy(favoriteTeam = abbr) } }
                            SettingsToggleRow("Continue watching", "Shows from apps that share progress with the TV.", s.watchContinue, colors) {
                                store.update { it.copy(watchContinue = !it.watchContinue) }
                            }
                            SettingsToggleRow("Live channels", "Live rows from TV apps, such as YouTube TV or ESPN.", s.watchLive, colors) {
                                store.update { it.copy(watchLive = !it.watchLive) }
                            }
                            // Order of the Home strip cards and the drawer sections.
                            s.watchOrder.forEachIndexed { i, id ->
                                val label = WATCH_SECTIONS.first { it.first == id }.second
                                SettingsRow("${i + 1}. $label", if (i == 0) "Card order on Home and in Watch Now." else "", colors) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        fun move(d: Int) = store.update { cur ->
                                            val o = cur.watchOrder.toMutableList()
                                            val j = i + d
                                            if (j in o.indices) o.add(j, o.removeAt(i))
                                            cur.copy(watchOrder = o)
                                        }
                                        Pill("↑", colors, selected = false) { move(-1) }
                                        Pill("↓", colors, selected = false) { move(+1) }
                                    }
                                }
                            }
                            // Live TV apps name local network stations by call sign (ABC = "WFAA 8"),
                            // so tune-in needs to know which shared channel is which network.
                            LOCAL_NETWORKS.forEach { net ->
                                val channels = state.watchNow.live.flatMap { g ->
                                    g.items.map { it.title to "${it.title} · ${state.apps[g.pkg]?.label ?: g.provider}" }
                                }.distinctBy { it.first }
                                SettingsChoiceRow(
                                    "Local $net station", "Which live channel carries $net where you are, for game tune-in.",
                                    s.localStations[net] ?: "Not set",
                                    listOf(SettingsPickerOption("", "Not set")) + channels.map { (title, note) -> SettingsPickerOption(title, title, note) },
                                    s.localStations[net] ?: "", colors,
                                ) { title ->
                                    store.update { it.copy(localStations = if (title.isBlank()) it.localStations - net else it.localStations + (net to title)) }
                                }
                            }
                            SettingsStatusRow(
                                "One-time setup",
                                "Continue watching and Live channels need the one-time setup: Set up in About & data › This TV, or on a computer: adb shell pm grant com.aktvhome.launcher android.permission.READ_TV_LISTINGS",
                                colors,
                            )
                        }
                        Page.BACKUP -> {
                            val b = state.backup
                            SettingsActionRow("Automatic backup", "Saved to Download/Haven on this TV whenever you change something. Last saved: ${backupTimeText(b.lastSaved)}.", "Back up now", colors) { b.backupNow() }
                            SettingsActionRow("Restore", if (b.backupExists()) "Bring back the setup saved ${backupTimeText(b.backupTime())}." else "No backup found yet.", "Restore", colors) { b.restore() }
                            SettingsActionRow(
                                "Reset Haven", "Back to first-run settings. Your backup is kept, so you can restore it.",
                                if (confirmReset) "Press again to reset" else "Reset", colors,
                            ) {
                                if (confirmReset) { b.reset(); confirmReset = false } else confirmReset = true
                            }
                            if (!b.canAccessFiles()) {
                                SettingsStatusRow("One-time setup", "So Haven can read its backup after a reinstall: use Set up in About & data › This TV, or run on a computer: adb shell appops set com.aktvhome.launcher MANAGE_EXTERNAL_STORAGE allow", colors)
                            }
                            if (b.problem.isNotBlank()) SettingsStatusRow("Backup problem", b.problem, colors)
                        }
                            Page.NBA_ALERTS -> NbaAlertsPage(state, colors, first)
                            Page.LIVE_CHANNELS -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs, each over the panel.
    if (editingName) {
        TextInputDialog(colors, "Your name", s.name,
            onSave = { n -> store.update { it.copy(name = n.trim()) }; editingName = false },
            onDismiss = { editingName = false })
    }
    pickingStation?.let { net ->
        // Every channel the live TV apps share, so the right local station can be picked.
        val channels = state.watchNow.live.flatMap { g -> g.items.map { it.title to "${it.title} · ${state.apps[g.pkg]?.label ?: g.provider}" } }.distinctBy { it.first }
        LaunchedEffect(Unit) { state.watchNow.tick(force = true) }
        CollectionPickerDialog(
            colors,
            names = listOf("" to "Not set") + channels,
            currentId = s.localStations[net] ?: "",
            onPick = { title ->
                store.update { it.copy(localStations = if (title.isBlank()) it.localStations - net else it.localStations + (net to title)) }
                pickingStation = null
            },
            onNew = null,
            onDismiss = { pickingStation = null },
            title = if (channels.isEmpty()) "No live channels shared yet" else "Local $net station",
        )
    }
    if (pickingTeam) {
        CollectionPickerDialog(
            colors,
            names = listOf("" to "None") + Nba.TEAMS,
            currentId = s.favoriteTeam,
            onPick = { abbr -> store.update { it.copy(favoriteTeam = abbr) }; pickingTeam = false },
            onNew = null,
            onDismiss = { pickingTeam = false },
            title = "Favorite team",
        )
    }
    if (editingKey) {
        TextInputDialog(colors, "Unsplash Access Key", s.unsplashKey,
            onSave = { k ->
                // Saving a key also switches Unsplash on, since that's why it was added.
                store.update { it.copy(unsplashKey = k.trim(), wallSources = it.wallSources + WallSource.UNSPLASH) }
                editingKey = false
            },
            onDismiss = { editingKey = false }, maxLength = 120)
    }
    if (editingDns) {
        TextInputDialog(colors, "Custom DNS hostname", s.dnsCustom.ifBlank { "dns.adguard-dns.com" },
            onSave = { h -> state.selectDns(DnsChoice.CUSTOM, h.trim()); editingDns = false },
            onDismiss = { editingDns = false })
    }
    collectionMenu?.let { c ->
        val firstItem = remember { FocusRequester() }
        GlassDialog(colors, c.name, onDismiss = { collectionMenu = null }) {
            Pill("Change icon", colors, modifier = Modifier.fillMaxWidth(), focusRequester = firstItem, icon = CollectionIcons.of(c.icon)) {
                collectionMenu = null; iconFor = c
            }
            if (c.isInputs) {
                Pill(if (c.hidden) "Show on home" else "Hide from home", colors, modifier = Modifier.fillMaxWidth()) {
                    layout.setCollectionHidden(c.id, !c.hidden); collectionMenu = null
                }
            } else {
                Pill("Rename", colors, modifier = Modifier.fillMaxWidth()) { collectionMenu = null; renaming = c }
                if (c.id != Defaults.OTHER_ID || countOf(c) == 0) {
                    Pill("Delete (apps go to Other)", colors, modifier = Modifier.fillMaxWidth()) {
                        park(); layout.deleteCollection(c.id); collectionMenu = null; pendingFocus = FIRST
                    }
                }
            }
            Pill("Cancel", colors, modifier = Modifier.fillMaxWidth()) { collectionMenu = null }
        }
        LaunchedEffect(c.id) { runCatching { firstItem.requestFocus() } }
    }
    iconFor?.let { c ->
        IconPickerDialog(colors, c.icon, onPick = { k -> layout.setIcon(c.id, k); iconFor = null }, onDismiss = { iconFor = null })
    }
    renaming?.let { c ->
        TextInputDialog(colors, "Rename collection", c.name,
            onSave = { layout.renameCollection(c.id, it); renaming = null },
            onDismiss = { renaming = null })
    }
    if (creating) {
        TextInputDialog(colors, "New collection", "",
            onSave = { name -> val id = layout.addCollection(name); creating = false; iconFor = layout.layout.collections.first { it.id == id } },
            onDismiss = { creating = false })
    }
    appMenu?.let { app ->
        val firstItem = remember { FocusRequester() }
        GlassDialog(colors, app.label, onDismiss = { appMenu = null }) {
            Pill("Move to collection…", colors, modifier = Modifier.fillMaxWidth(), focusRequester = firstItem) {
                appMenu = null; pickerFor = app
            }
            Pill("Hide", colors, modifier = Modifier.fillMaxWidth()) { park(); layout.hide(app.pkg); appMenu = null; pendingFocus = FIRST }
            Pill("Cancel", colors, modifier = Modifier.fillMaxWidth()) { appMenu = null }
        }
        LaunchedEffect(app.pkg) { runCatching { firstItem.requestFocus() } }
    }
    pickerFor?.let { app ->
        CollectionPickerDialog(
            colors,
            names = cols.filter { !it.isInputs }.map { it.id to it.name },
            currentId = layout.collectionOf(app.pkg)?.id,
            onPick = { id -> park(); layout.moveToCollection(app.pkg, id); pickerFor = null; pendingFocus = FIRST },
            onNew = { pickerFor = null; creating = true },
            onDismiss = { pickerFor = null },
        )
    }

    LaunchedEffect(Unit) { runCatching { navFocus.getValue(SettingsGroup.APPEARANCE).requestFocus() } }
}

private const val FIRST = "__first__"

private fun openedSubtitle(c: AppCollection, count: Int, inputs: Int): String =
    if (c.isInputs) "$inputs inputs · show or hide this row, change its icon"
    else "$count apps · right arrow to the arrows to reorder, OK for more"

@Composable
private fun NavItem(group: SettingsGroup, colors: ThemeColors, selected: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) colors.pillInk else colors.ink
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    focused -> colors.pill
                    selected -> colors.glass
                    else -> Color.Transparent
                },
            )
            .border(1.dp, if (selected && !focused) colors.accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(group.icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(group.label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(group.note, color = ink.copy(alpha = 0.65f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (selected) "•" else "›", color = ink.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun SettingsTab(
    text: String,
    selected: Boolean,
    colors: ThemeColors,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val ink = if (focused) colors.pillInk else colors.ink
    Box(
        modifier
            .clip(shape)
            .background(if (focused) colors.pill else if (selected) colors.glass else Color.Transparent)
            .border(1.dp, if (selected) colors.accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    note: String,
    colors: ThemeColors,
    control: @Composable () -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) colors.glass else colors.glass.copy(alpha = colors.glass.alpha * 0.8f))
            .border(1.dp, if (active) colors.accent.copy(alpha = 0.6f) else colors.edge, shape)
            .onFocusChanged { active = it.hasFocus }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(note, color = colors.inkSoft, fontSize = 10.sp)
        }
        control()
    }
}

@Composable
internal fun ChoicePills(
    values: List<String>,
    selected: String,
    colors: ThemeColors,
    focusRequester: FocusRequester? = null,
    onChoose: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { index, value ->
            Pill(
                value, colors, selected = value == selected,
                focusRequester = if (index == 0) focusRequester else null,
            ) { onChoose(value) }
        }
    }
}

/** Theme choice with the real Home UI scaled down, frozen and made non-interactive. */
@Composable
private fun ThemeCard(theme: ThemeId, state: HavenState, chosen: Boolean, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(colors, modifier.settingSearchTarget(theme.label), selected = chosen, onClick = onClick) { ink ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val c = Palettes.colors(theme, state.dayness())
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.bgMid),
            ) {
                val scale = maxWidth.value / 1280f
                CompositionLocalProvider(LocalTvInteractionsEnabled provides false) {
                    Box(
                        Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(1280.dp, 720.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                    ) {
                        when (theme) {
                            ThemeId.KINGDOM -> KingdomHome(state, c, preview = true)
                            ThemeId.TIDE -> TideHome(state, c, preview = true)
                        }
                    }
                }
            }
            Row {
                Text(theme.label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (chosen) "✓ Selected" else "Select →", color = ink.copy(alpha = 0.75f), fontSize = 11.sp)
            }
        }
    }
}

/** A card-size choice with a 16:9 sample drawn at that size's proportion. */
@Composable
private fun SizeCard(size: CardSize, chosen: Boolean, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(colors, modifier.height(128.dp), selected = chosen, onClick = onClick) { ink ->
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.BottomStart) {
                // Large draws at 88 dp wide, the others in proportion.
                val w = (88f * size.width / CardSize.LARGE.width).dp
                Box(Modifier.size(w, w * 9f / 16f).clip(RoundedCornerShape(6.dp)).background(ink.copy(alpha = 0.35f)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(size.label, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("${size.width} dp cards", color = ink.copy(alpha = 0.7f), fontSize = 11.sp)
                }
                if (chosen) Text("✓", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The Collections page: one column of rows. Each row is OK to open (hold for options),
 * and right arrow walks onto its ↑ ↓ buttons, which move it immediately.
 */
@Composable
private fun CollectionsGrid(
    cols: List<AppCollection>,
    colors: ThemeColors,
    first: Modifier,
    target: (String) -> FocusRequester,
    countLabel: (AppCollection) -> String,
    onOpen: (AppCollection) -> Unit,
    onMenu: (AppCollection) -> Unit,
    onShift: (AppCollection, Int, String) -> Unit,
    onNew: () -> Unit,
) {
    // Tiles are drawn by position (keyed reordering doesn't re-place FlowRow children). After a
    // move, focus follows the collection through its named targets instead.
    TwoColumns {
        cols.forEachIndexed { i, c ->
            run {
                ArrowTile(
                    title = c.name, subtitle = countLabel(c), colors = colors,
                    image = null, icon = c.icon,
                    mainFocus = target("col:${c.id}"),
                    upFocus = target("up:${c.id}"), downFocus = target("down:${c.id}"),
                    canUp = i > 0, canDown = i < cols.lastIndex,
                    modifier = Modifier.weight(1f).then(if (i == 0) first else Modifier),
                    onOpen = { onOpen(c) }, onMenu = { onMenu(c) },
                    onUp = { onShift(c, -1, "up") }, onDown = { onShift(c, +1, "down") },
                )
            }
        }
        GlassCard(colors, Modifier.weight(1f).height(58.dp), onClick = onNew) { ink ->
            Text("+ New collection", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TwoColumns(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        maxItemsInEachRow = 1,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A tile with a main area plus ↑ ↓ buttons, used for both collections and apps. */
@Composable
private fun ArrowTile(
    title: String,
    subtitle: String?,
    colors: ThemeColors,
    image: ImageBitmap?,
    icon: String?,
    mainFocus: FocusRequester,
    upFocus: FocusRequester,
    downFocus: FocusRequester,
    canUp: Boolean,
    canDown: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(58.dp)
            .clip(shape)
            .background(colors.glass)
            .border(1.dp, colors.edge, shape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val ink = if (focused) colors.pillInk else colors.ink
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(mainFocus)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) colors.pill else Color.Transparent)
                .onFocusChanged { focused = it.isFocused }
                .tvClickable(onLongClick = onMenu, onClick = onOpen)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (image != null) Image(image, null, Modifier.size(28.dp))
            if (icon != null) IconBadge(icon, colors, size = 30.dp, tint = if (focused) colors.accent else colors.ink)
            Column(Modifier.weight(1f)) {
                Text(title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = ink.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
            }
        }
        ArrowButton(Icons.Rounded.KeyboardArrowUp, "Move up", canUp, upFocus, colors, onUp)
        ArrowButton(Icons.Rounded.KeyboardArrowDown, "Move down", canDown, downFocus, colors, onDown)
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, label: String, enabled: Boolean, focus: FocusRequester, colors: ThemeColors, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(34.dp)
            .focusRequester(focus)
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            // Stays focusable at the ends so left/right never skip over it; it just does nothing there.
            .tvClickable { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (focused) colors.pillInk else colors.ink
        Icon(icon, label, tint = ink.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun Switch(on: Boolean, colors: ThemeColors) {
    Box(
        Modifier
            .size(40.dp, 22.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) colors.accent else colors.edge),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(3.dp).size(16.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

@Composable
private fun AboutCard(colors: ThemeColors, first: Modifier) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    SettingsStatusRow("Haven", "Your TV. Your space. Your way.", colors, first)
    SettingsStatusRow("Version", version, colors)
    SettingsStatusRow("Package", context.packageName, colors)
    SettingsStatusRow("Android TV", "Designed for Android TV", colors)
}

@Composable
private fun Chip(text: String, colors: ThemeColors) {
    Text(
        text, color = colors.ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.glass).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
