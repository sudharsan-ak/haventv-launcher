@file:OptIn(ExperimentalLayoutApi::class) // FlowRow, for the two-column grids

package com.aktvhome.launcher.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
import com.aktvhome.launcher.data.PrivateDns
import com.aktvhome.launcher.data.Motion
import com.aktvhome.launcher.data.ThemeId
import com.aktvhome.launcher.ui.CollectionIcons
import com.aktvhome.launcher.ui.CollectionPickerDialog
import com.aktvhome.launcher.ui.GlassDialog
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.IconBadge
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.TextInputDialog
import com.aktvhome.launcher.ui.clockText
import com.aktvhome.launcher.ui.kingdom.KingdomBackdrop
import com.aktvhome.launcher.ui.theme.Palettes
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tide.TideBackdrop
import com.aktvhome.launcher.ui.tvClickable

private enum class Page(val label: String, val icon: ImageVector, val subtitle: String) {
    THEME("Theme", Icons.Rounded.Palette, "Choose the world you want to come home to."),
    DAY_NIGHT("Day & night", Icons.Rounded.BrightnessAuto, "Set how Haven moves between day and night."),
    MOTION("Motion", Icons.Rounded.Animation, "How much life the scenes have."),
    CLOCK("Clock", Icons.Rounded.Schedule, "How the time reads on every theme."),
    CARDS("Card size", Icons.Rounded.AspectRatio, "How big app cards and dock icons are."),
    WALLPAPER("Wallpaper", Icons.Rounded.Wallpaper, "Rotating photos behind your theme."),
    DNS("Private DNS", Icons.Rounded.Shield, "Encrypted DNS for the whole TV, with ad and tracker blocking."),
    HOME_BUTTON("Home button", Icons.Rounded.Home, "Make the remote's Home button open Haven."),
    GREETING("Greeting", Icons.Rounded.Person, "The hello at the top of your home."),
    COLLECTIONS("Collections", Icons.Rounded.ViewModule, "Arrange the rows of your home."),
    HIDDEN("Hidden apps", Icons.Rounded.VisibilityOff, "Choose what stays out of sight."),
    ABOUT("About", Icons.Rounded.Info, "A personal TV launcher, designed around what matters."),
}

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

    var page by remember { mutableStateOf(Page.THEME) }
    var contentFocused by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    val navFocus = remember { Page.entries.associateWith { FocusRequester() } }
    // Right/OK from the menu always lands on the page's first item.
    val contentFirst = remember { FocusRequester() }
    val first = Modifier.focusRequester(contentFirst)

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
        runCatching { if (k == FIRST) contentFirst.requestFocus() else target(k).requestFocus() }
        pendingFocus = null
    }

    // Dialog state.
    var editingName by remember { mutableStateOf(false) }
    var editingDns by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }
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
            contentFocused -> { runCatching { navFocus.getValue(page).requestFocus() } }
            else -> close()
        }
    }

    val cols = layout.layout.collections
    val opened = cols.firstOrNull { it.id == openId }
    fun countOf(c: AppCollection) = c.apps.count { it in state.apps && it !in layout.layout.hidden }

    HavenOverlay(dim = 0.45f, onDismiss = onBack) {
        Row(
            Modifier
                .align(Alignment.Center)
                .width(800.dp)
                .height(452.dp)
                .clip(RoundedCornerShape(28.dp))
                // Solid enough that the home's own text doesn't show through and fight the panel's.
                .background(colors.surface.copy(alpha = 0.97f))
                .border(1.dp, colors.edge, RoundedCornerShape(28.dp)),
        ) {
            // Menu.
            Column(
                Modifier.width(228.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 14.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Eyebrow("Haven · Settings", colors)
                Text("Personalize", color = colors.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Fine-tune your home without losing sight of it.", color = colors.inkSoft, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                Page.entries.forEach { p ->
                    NavItem(
                        p, colors, selected = p == page,
                        modifier = Modifier
                            .focusRequester(navFocus.getValue(p))
                            .focusProperties { right = contentFirst },
                        onFocused = { if (p != page) openId = null; page = p },
                        onClick = { runCatching { contentFirst.requestFocus() } },
                    )
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.edge))

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
                        Eyebrow(
                            if (page == Page.COLLECTIONS && opened != null) "Collections / ${opened.name}" else "${page.label} / live preview",
                            colors,
                        )
                        Text(
                            if (page == Page.COLLECTIONS && opened != null) opened.name else page.label,
                            color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (page == Page.COLLECTIONS && opened != null) openedSubtitle(opened, countOf(opened), state.inputs.size)
                            else page.subtitle,
                            color = colors.inkSoft, fontSize = 11.sp,
                        )
                    }
                    CloseButton(colors, Modifier.focusRequester(closeFocus), onClick = close)
                }
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (page) {
                        Page.THEME -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ThemeId.entries.forEachIndexed { i, t ->
                                    ThemeCard(
                                        t, state, chosen = s.theme == t, colors = colors,
                                        modifier = Modifier.weight(1f).then(if (i == 0) first else Modifier),
                                    ) { store.update { it.copy(theme = t) } }
                                }
                            }
                            InfoStrip("See it before you commit", "The home behind this panel switches the moment you pick.", colors)
                        }
                        Page.DAY_NIGHT -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceCard("Auto", "Day 6 AM to 6 PM, blended", Icons.Rounded.BrightnessAuto, s.dayMode == DayMode.AUTO, colors, Modifier.weight(1f).then(first)) {
                                    store.update { it.copy(dayMode = DayMode.AUTO) }
                                }
                                ChoiceCard("Day", "Bright and clear", Icons.Rounded.LightMode, s.dayMode == DayMode.DAY, colors, Modifier.weight(1f)) {
                                    store.update { it.copy(dayMode = DayMode.DAY) }
                                }
                                ChoiceCard("Night", "Easy on your eyes", Icons.Rounded.DarkMode, s.dayMode == DayMode.NIGHT, colors, Modifier.weight(1f)) {
                                    store.update { it.copy(dayMode = DayMode.NIGHT) }
                                }
                            }
                            InfoStrip("Automatic switching at 6 AM and 6 PM", "Colors ease over about an hour around each switch instead of flipping.", colors)
                        }
                        Page.MOTION -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceCard("Full", "Glow, arcs and waves at full life", Icons.Rounded.Animation, s.motion == Motion.FULL, colors, Modifier.weight(1f).then(first)) {
                                    store.update { it.copy(motion = Motion.FULL) }
                                }
                                ChoiceCard("Reduced", "Half speed, calmer", Icons.Rounded.SlowMotionVideo, s.motion == Motion.REDUCED, colors, Modifier.weight(1f)) {
                                    store.update { it.copy(motion = Motion.REDUCED) }
                                }
                                ChoiceCard("Off", "Still scenes", Icons.Rounded.StopCircle, s.motion == Motion.OFF, colors, Modifier.weight(1f)) {
                                    store.update { it.copy(motion = Motion.OFF) }
                                }
                            }
                            InfoStrip("Haven's own motion", "Works even though your TV's system animations are switched off.", colors)
                        }
                        Page.CLOCK -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceCard("12-hour", clockText(state.now, false), Icons.Rounded.Schedule, !s.clock24, colors, Modifier.weight(1f).then(first)) {
                                    store.update { it.copy(clock24 = false) }
                                }
                                ChoiceCard("24-hour", clockText(state.now, true), Icons.Rounded.Schedule, s.clock24, colors, Modifier.weight(1f)) {
                                    store.update { it.copy(clock24 = true) }
                                }
                            }
                        }
                        Page.CARDS -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CardSize.entries.forEachIndexed { i, size ->
                                    SizeCard(
                                        size, chosen = s.cardSize == size, colors = colors,
                                        modifier = Modifier.weight(1f).then(if (i == 0) first else Modifier),
                                    ) { store.update { it.copy(cardSize = size) } }
                                }
                            }
                            InfoStrip("Sized live", "The cards behind this panel resize the moment you pick. Large is the original size.", colors)
                        }
                        Page.WALLPAPER -> {
                            val walls = state.wallpapers
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceCard(
                                    "Kingdom", "Background: ${s.bgKingdom.label}", Icons.Rounded.Wallpaper,
                                    s.bgKingdom == Background.PHOTO, colors, Modifier.weight(1f).then(first), height = 92.dp,
                                ) {
                                    store.update { it.copy(bgKingdom = if (it.bgKingdom == Background.PHOTO) Background.SCENE else Background.PHOTO) }
                                    walls.tick()
                                }
                                ChoiceCard(
                                    "Tide", "Background: ${s.bgTide.label}", Icons.Rounded.Wallpaper,
                                    s.bgTide == Background.PHOTO, colors, Modifier.weight(1f), height = 92.dp,
                                ) {
                                    store.update { it.copy(bgTide = if (it.bgTide == Background.PHOTO) Background.SCENE else Background.PHOTO) }
                                    walls.tick()
                                }
                                ChoiceCard("Next photo now", "Fetch a new one", Icons.Rounded.Refresh, false, colors, Modifier.weight(1f), height = 92.dp) {
                                    walls.tick(force = true)
                                }
                            }
                            Eyebrow("Sources", colors)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                WallSource.entries.forEach { src ->
                                    val on = src in s.wallSources
                                    val sub = if (src == WallSource.UNSPLASH && s.unsplashKey.isBlank()) "Needs your key (below)" else src.note
                                    ChoiceCard(src.label, sub, null, on, colors, Modifier.weight(1f), height = 92.dp) {
                                        store.update { it.copy(wallSources = if (on) it.wallSources - src else it.wallSources + src) }
                                    }
                                }
                            }
                            Eyebrow("Unsplash topics", colors)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                WALL_TOPICS.forEach { (label, _) ->
                                    val on = label in s.wallTopics
                                    Pill(label, colors, selected = on) {
                                        store.update { it.copy(wallTopics = if (on) it.wallTopics - label else it.wallTopics + label) }
                                    }
                                }
                            }
                            Eyebrow("Change every", colors)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                WallInterval.entries.forEach { iv ->
                                    ChoiceCard(iv.label, "", null, s.wallInterval == iv, colors, Modifier.weight(1f), height = 72.dp) {
                                        store.update { it.copy(wallInterval = iv) }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceCard(
                                    "Unsplash key",
                                    if (s.unsplashKey.isBlank()) "Not set. OK to paste it" else "Set (ends in ${s.unsplashKey.takeLast(4)})",
                                    Icons.Rounded.Key, s.unsplashKey.isNotBlank(), colors, Modifier.weight(1f), height = 92.dp,
                                ) { editingKey = true }
                                ChoiceCard(
                                    "Photo credit", if (s.wallCredit) "Shown in the corner" else "Hidden",
                                    Icons.Rounded.Info, s.wallCredit, colors, Modifier.weight(1f), height = 92.dp,
                                ) { store.update { it.copy(wallCredit = !it.wallCredit) } }
                            }
                            InfoStrip(
                                "Now showing",
                                listOf(walls.credit, walls.status).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "No photo yet" },
                                colors,
                            )
                        }
                        Page.HOME_BUTTON -> {
                            val ctx = LocalContext.current
                            var on by remember { mutableStateOf(HomeOverride.isOn(ctx)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceCard(
                                    "Haven on Home", if (on) "On: Home opens Haven" else "Off: Home opens Google TV",
                                    Icons.Rounded.Home, on, colors, Modifier.weight(1f).then(first),
                                ) {
                                    if (HomeOverride.set(ctx, !on)) on = HomeOverride.isOn(ctx)
                                }
                            }
                            InfoStrip(
                                "Turn off Projectivy's override",
                                "In Projectivy's settings, switch off \"Override current launcher\" so the two don't fight over Home.",
                                colors,
                            )
                            InfoStrip(
                                "What it does",
                                "Google TV always opens its own home first. This watches only for that page and opens Haven instead. Your other accessibility services are untouched.",
                                colors,
                            )
                        }
                        Page.DNS -> {
                            val dns by rememberDnsView(state)
                            if (!state.canSetDns()) {
                                InfoStrip("One-time setup needed", "Run on your PC: ${PrivateDns.GRANT_COMMAND}", colors)
                            }
                            DnsChoice.entries.chunked(3).forEachIndexed { r, row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEachIndexed { c, choice ->
                                        val sub = when {
                                            choice == DnsChoice.CUSTOM && s.dnsCustom.isNotBlank() -> s.dnsCustom
                                            else -> choice.host ?: choice.note
                                        }
                                        ChoiceCard(
                                            choice.label, sub, null, dns.active == choice, colors,
                                            Modifier.weight(1f).then(if (r == 0 && c == 0) first else Modifier),
                                            height = 92.dp,
                                        ) { if (choice == DnsChoice.CUSTOM) editingDns = true else state.selectDns(choice) }
                                    }
                                }
                            }
                            ChoiceCard(
                                "Keep enforced",
                                if (s.dnsEnforce) "On: restores ${s.dnsChoice.label} at startup and whenever Haven opens" else "Off",
                                Icons.Rounded.Shield, s.dnsEnforce, colors, Modifier.fillMaxWidth(), height = 92.dp,
                            ) { store.update { it.copy(dnsEnforce = !it.dnsEnforce) } }
                            InfoStrip("Status", dns.status.text, colors)
                        }
                        Page.GREETING -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceCard("Your name", s.name.ifBlank { "Not set" }, Icons.Rounded.Person, false, colors, Modifier.weight(1f).then(first)) {
                                    editingName = true
                                }
                                ChoiceCard(
                                    "Show greeting", if (s.showGreeting) "On" else "Off",
                                    if (s.showGreeting) Icons.Rounded.LightMode else Icons.Rounded.VisibilityOff,
                                    s.showGreeting, colors, Modifier.weight(1f),
                                ) { store.update { it.copy(showGreeting = !it.showGreeting) } }
                            }
                            InfoStrip("Preview", state.greetingLine() ?: "Greeting is off", colors)
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
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChoiceCard(
                                    "Show on home", if (opened.hidden) "Hidden" else "Shown",
                                    CollectionIcons.of(opened.icon), !opened.hidden, colors, Modifier.weight(1f).then(first),
                                ) { layout.setCollectionHidden(opened.id, !opened.hidden) }
                                ChoiceCard("Icon", "Change the row's icon", CollectionIcons.of(opened.icon), false, colors, Modifier.weight(1f)) {
                                    iconFor = opened
                                }
                            }
                            InfoStrip("Your inputs", state.inputs.joinToString(" · ") { it.label }.ifBlank { "None found" }, colors)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Pill("Change icon", colors, focusRequester = contentFirst, icon = CollectionIcons.of(opened.icon)) { iconFor = opened }
                                Pill("Rename", colors) { renaming = opened }
                            }
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
                            listed.chunked(2).forEachIndexed { r, pair ->
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
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        Page.ABOUT -> AboutCard(colors, first)
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

    LaunchedEffect(Unit) { runCatching { navFocus.getValue(Page.THEME).requestFocus() } }
}

private const val FIRST = "__first__"

private fun openedSubtitle(c: AppCollection, count: Int, inputs: Int): String =
    if (c.isInputs) "$inputs inputs · show or hide this row, change its icon"
    else "$count apps · right arrow to the arrows to reorder, OK for more"

@Composable
private fun NavItem(p: Page, colors: ThemeColors, selected: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
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
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(p.icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Text(p.label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(if (selected) "•" else "›", color = ink.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

/** Theme choice with a small preview painted by that theme's own backdrop code. */
@Composable
private fun ThemeCard(theme: ThemeId, state: HavenState, chosen: Boolean, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(colors, modifier, selected = chosen, onClick = onClick) { ink ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val c = Palettes.colors(theme, state.dayness())
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.verticalGradient(listOf(c.bgTop, c.bgMid, c.bgBottom))),
            ) {
                when (theme) {
                    ThemeId.KINGDOM -> {
                        KingdomBackdrop(c, c.glow, Motion.OFF, Modifier.fillMaxSize())
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("What are we watching?", color = c.ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                repeat(5) { Box(Modifier.size(28.dp, 18.dp).clip(RoundedCornerShape(4.dp)).background(c.glass)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                state.apps.values.take(5).forEach { a ->
                                    Box(Modifier.size(32.dp, 18.dp).clip(RoundedCornerShape(4.dp)).background(a.color))
                                }
                            }
                        }
                    }
                    ThemeId.TIDE -> {
                        TideBackdrop(c, Motion.OFF, Modifier.fillMaxSize())
                        Text(
                            clockText(state.now, state.settingsStore.settings.clock24).removeSuffix(" AM").removeSuffix(" PM"),
                            color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Thin,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                        )
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
 * The Collections page: two columns of tiles. Each tile is OK to open (hold for options),
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
        maxItemsInEachRow = 2,
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
private fun Switch(on: Boolean, colors: ThemeColors) {
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
    GlassCard(colors, Modifier.fillMaxWidth().then(first), onClick = {}) { ink ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(R.drawable.ic_haven), null, Modifier.size(52.dp))
            Text("Haven", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Your TV. Your space. Your way.", color = ink.copy(alpha = 0.75f), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Version $version", colors)
                Chip(context.packageName, colors)
                Chip("Designed for Android TV", colors)
            }
        }
    }
}

@Composable
private fun Chip(text: String, colors: ThemeColors) {
    Text(
        text, color = colors.ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.glass).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
