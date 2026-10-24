package com.aktvhome.launcher.ui.kingdom

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import com.aktvhome.launcher.ui.rememberHeadline
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import com.aktvhome.launcher.ui.CollectionIcons
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Background
import com.aktvhome.launcher.data.Motion
import com.aktvhome.launcher.ui.PhotoBackdrop
import com.aktvhome.launcher.ui.homeShown
import com.aktvhome.launcher.ui.AppCard
import com.aktvhome.launcher.ui.AppMenuOverlays
import com.aktvhome.launcher.ui.GearButton
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.IconBadge
import com.aktvhome.launcher.ui.InputCard
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.ShownCollection
import com.aktvhome.launcher.ui.clockText
import com.aktvhome.launcher.ui.moveKeys
import com.aktvhome.launcher.ui.rememberAmbientSeconds
import com.aktvhome.launcher.ui.onKeyDown
import com.aktvhome.launcher.ui.watch.SmartStrip
import com.aktvhome.launcher.ui.watch.stripVisible
import com.aktvhome.launcher.ui.rememberWrapRow
import com.aktvhome.launcher.ui.rememberAppGrid
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.key
import android.view.KeyEvent
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private val SIDE = 48.dp

@OptIn(ExperimentalFoundationApi::class) // bringIntoViewRequester for lower app lines
@Composable
fun KingdomHome(state: HavenState, colors: ThemeColors, preview: Boolean = false) {
    val settings = state.settingsStore.settings
    val shown = state.shownCollections()
    val current = state.currentCollection(shown)
    val tiles = rememberWrapRow(shown.size)
    val currentIndex = shown.indexOfFirst { it.collection.id == current?.collection?.id }.coerceAtLeast(0)
    val showStrip = state.stripVisible()
    val stripFirst = remember { FocusRequester() }
    // Under the tiles: HDMI inputs in one row, or the apps in lines sized by card size.
    // One instance each so the tiles can move focus into them.
    val items = rememberWrapRow(if (current?.isInputs == true) state.inputs.size else 0)
    val grid = rememberAppGrid(if (current?.isInputs == true) 0 else current?.apps?.size ?: 0, settings.cardSize.columns)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Collections and apps always show with the full header above them.
    val toTop: () -> Unit = { scope.launch { scroll.scrollTo(0) } }

    // The background glow leans toward the focused app's own color.
    val glow by animateColorAsState(
        state.focusedApp?.color?.copy(alpha = colors.glow.alpha) ?: colors.glow, tween(900), label = "glow",
    )

    // Wallpaper view (Back on Home) fades the layout out, leaving just the background.
    val homeAlpha = homeShown(state)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bgTop, colors.bgMid, colors.bgBottom))),
    ) {
        val photo = state.wallpapers.current
        if (settings.bgKingdom == Background.PHOTO && photo != null) {
            PhotoBackdrop(photo, state.dayness(), state.wallpapers.credit.takeIf { settings.wallCredit }, tint = homeAlpha)
        } else {
            KingdomBackdrop(colors, glow, if (preview) Motion.OFF else settings.motion, Modifier.fillMaxSize())
        }

        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = homeAlpha }
                .verticalScroll(scroll)
                .padding(top = 30.dp, bottom = 24.dp),
        ) {
            val side = Modifier.padding(horizontal = SIDE)
            Row(side, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(clockText(state.now, settings.clock24), color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(16.dp))
                // Down from the gear: the Watch Now strip if shown, else the collection you were on.
                GearButton(colors, Modifier.focusProperties { down = if (showStrip) stripFirst else tiles.requester(currentIndex) }) {
                    state.overlay = Overlay.QUICK
                }
            }

            Spacer(Modifier.height(14.dp))
            state.greetingLine()?.let { Text(it, color = colors.inkSoft, fontSize = 14.sp, modifier = side) }
            // Follows your team's game live, otherwise fits the time of day, with notable games
            // taking turns; crossfades when it changes.
            Crossfade(targetState = rememberHeadline(state), animationSpec = tween(600), label = "headline") { line ->
                Text(
                    line, color = colors.ink, fontSize = 34.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = side, maxLines = 1,
                )
            }

            if (showStrip) {
                Spacer(Modifier.height(14.dp))
                // Above the tiles, so Down from a tile still goes straight to its apps.
                SmartStrip(
                    state, colors, stripFirst,
                    side.fillMaxWidth().onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN) { tiles.focus(currentIndex) },
                )
            }

            Spacer(Modifier.height(14.dp))
            LazyRow(
                state = tiles.state,
                modifier = tiles.keys,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = SIDE),
            ) {
                itemsIndexed(shown, key = { _, sc -> sc.collection.id }) { i, sc ->
                    CollectionTab(
                        sc, colors,
                        selected = sc.collection.id == current?.collection?.id,
                        // Down enters this collection at the app last opened from it (or the first).
                        modifier = tiles.item(i).onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (sc.isInputs) items.focus(0) else grid.focus(state.entryIndex(sc))
                        },
                        onFocused = {
                            tiles.onItemFocused(i)
                            state.selectedCollection = sc.collection.id
                            toTop()
                        },
                    )
                }
            }

            // A hairline under the tabs, like Settings; the open tab already names the row below.
            Box(side.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(colors.edge))
            if (current != null) {
                Spacer(Modifier.height(4.dp))
                when {
                    current.isInputs -> {
                        val row = items
                        LazyRow(
                            state = row.state,
                            modifier = row.keys.onKeyDown(KeyEvent.KEYCODE_DPAD_UP) { tiles.focus(currentIndex) },
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 12.dp, horizontal = SIDE),
                        ) {
                            itemsIndexed(state.inputs, key = { _, input -> input.id }) { i, input ->
                                InputCard(
                                    input.label, colors, width = settings.cardSize.width.dp, modifier = row.item(i),
                                    onFocused = { row.onItemFocused(i); state.focusedApp = null; toTop() },
                                ) { state.switchTo(input) }
                            }
                        }
                    }
                    current.apps.isEmpty() -> Text(
                        "Empty. Hold OK on any app and choose \"Move to collection\".",
                        color = colors.inkSoft, fontSize = 13.sp, modifier = side.padding(vertical = 30.dp),
                    )
                    else -> {
                        val visible = current.apps.map { it.pkg }
                        val cols = settings.cardSize.columns
                        grid.paused = state.moving != null
                        Column(
                            // Up from the first line goes back to this collection's tile (not while moving an app).
                            side.then(grid.keys)
                                .onKeyDown(KeyEvent.KEYCODE_DPAD_UP, { state.moving == null && grid.inTopRow }) {
                                    tiles.focus(currentIndex)
                                }
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            current.apps.chunked(cols).forEachIndexed { r, line ->
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    line.forEachIndexed { c, app ->
                                        val i = r * cols + c
                                        key(app.pkg) {
                                            val bring = remember { BringIntoViewRequester() }
                                            AppCard(
                                                app, colors, width = settings.cardSize.width.dp,
                                                moving = state.moving == app.pkg,
                                                modifier = grid.item(i).bringIntoViewRequester(bring).moveKeys(state, app.pkg, visible),
                                                onFocused = {
                                                    grid.onItemFocused(i)
                                                    state.focusedApp = app
                                                    // The first line keeps the header in view; lower lines scroll up to show.
                                                    if (r == 0) toTop() else scope.launch { bring.bringIntoView() }
                                                },
                                                onClick = { state.launch(app) },
                                                onLongClick = { state.menuFor = app },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    AppMenuOverlays(state, colors)

    // Closing settings returns focus to the collection you were on before opening them.
    LaunchedEffect(state.overlay) {
        if (state.overlay == Overlay.NONE && shown.isNotEmpty()) {
            delay(80) // let the closing window hand focus back first
            tiles.state.scrollToItem(currentIndex)
            runCatching { tiles.requester(currentIndex).requestFocus() }
        }
    }

    LaunchedEffect(shown.isNotEmpty(), state.homePresses) {
        if (shown.isNotEmpty()) {
            scroll.scrollTo(0)
            tiles.state.scrollToItem(0)
            runCatching { tiles.requester(0).requestFocus() }
        }
    }
}

/**
 * A collection as a compact tab, styled like the tabs in Settings: the open collection gets a
 * solid light background, focus adds an accent ring, the rest are plain text with an icon.
 */
@Composable
private fun CollectionTab(
    sc: ShownCollection,
    colors: ThemeColors,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val solid = focused || selected
    val ink = if (solid) colors.pillInk else colors.ink
    Row(
        modifier
            .height(42.dp)
            .clip(shape)
            .background(if (solid) colors.pill.copy(alpha = if (focused) 1f else 0.88f) else Color.Transparent)
            .border(2.dp, if (focused) colors.accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onClick = onFocused)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CollectionIcons.of(sc.collection.icon), null, tint = ink, modifier = Modifier.size(18.dp))
        Text(sc.collection.name, color = ink, fontSize = 15.sp, fontWeight = if (solid) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

/** Soft pulsing glow plus the sweeping arc across the top. Also drawn small in the theme picker. */
@Composable
fun KingdomBackdrop(colors: ThemeColors, glow: Color, motion: Motion, modifier: Modifier) {
    val seconds = rememberAmbientSeconds(motion)

    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.52f, h * 0.05f)
            val arc = Path().apply {
                moveTo(w * 0.135f, h * 0.52f)
                cubicTo(w * 0.27f, h * 0.07f, w * 0.73f, h * 0.07f, w * 0.885f, h * 0.43f)
            }
            val len = PathMeasure().apply { setPath(arc, false) }.length
            // 5 px on a 1080p screen, scaled down for the small picker previews.
            val stroke = (w / 384f).coerceAtLeast(1f)
            onDrawBehind {
                val t = seconds.value
                // Glow breathes over 5 s; the arc draws itself on and off over 9 s.
                val pulse = if (motion == Motion.OFF) 0.8f else 0.8f + 0.2f * sin(t * 2 * PI.toFloat() / 5f)
                drawCircle(
                    Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * pulse), Color.Transparent), center, w * 0.45f),
                    radius = w * 0.45f, center = center,
                )
                drawPath(arc, colors.accent.copy(alpha = 0.22f), style = Stroke(width = stroke * 0.4f))
                val sweep = if (motion == Motion.OFF) 0f else (t / 9f) % 1f
                drawPath(
                    arc, colors.accent.copy(alpha = 0.8f),
                    style = Stroke(
                        width = stroke, cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(len, len), len * 2 * (1f - sweep)),
                    ),
                )
            }
        },
    )
}
