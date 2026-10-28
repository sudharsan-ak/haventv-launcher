package com.aktvhome.launcher.ui.tide

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.AppEntry
import com.aktvhome.launcher.data.Background
import com.aktvhome.launcher.data.Motion
import com.aktvhome.launcher.ui.PhotoBackdrop
import com.aktvhome.launcher.ui.homeShown
import com.aktvhome.launcher.ui.AppMenuOverlays
import com.aktvhome.launcher.ui.CollectionIcons
import com.aktvhome.launcher.ui.GearButton
import com.aktvhome.launcher.ui.SearchButton
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.clockText
import com.aktvhome.launcher.ui.rememberHeadline
import com.aktvhome.launcher.ui.moveKeys
import com.aktvhome.launcher.ui.rememberAmbientSeconds
import com.aktvhome.launcher.ui.onKeyDown
import com.aktvhome.launcher.ui.watch.SmartStrip
import com.aktvhome.launcher.ui.watch.stripVisible
import com.aktvhome.launcher.ui.rememberWrapRow
import android.view.KeyEvent
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun TideHome(state: HavenState, colors: ThemeColors, preview: Boolean = false) {
    val settings = state.settingsStore.settings
    val motion = if (preview) Motion.OFF else settings.motion
    val shown = state.shownCollections()
    val current = state.currentCollection(shown)
    // Chips: every shown collection, Inputs included. Settings is the gear at the top right, as on every theme.
    val chips = rememberWrapRow(shown.size)
    val currentIndex = shown.indexOfFirst { it.collection.id == current?.collection?.id }.coerceAtLeast(0)
    val showStrip = state.stripVisible()
    val stripFirst = remember { FocusRequester() }
    // The dock (apps, or HDMI inputs). One instance so the chips can move focus into it.
    val dockRow = rememberWrapRow(if (current?.isInputs == true) state.inputs.size else current?.apps?.size ?: 0)

    // The greeting fades in each time home comes back into view; with Motion off it just appears.
    val greetAlpha = remember { Animatable(1f) }
    LaunchedEffect(state.arrivals, motion) {
        if (motion == Motion.OFF) {
            greetAlpha.snapTo(1f)
        } else {
            greetAlpha.snapTo(0f)
            withContext(RealSpeed) { greetAlpha.animateTo(1f, tween(1_200)) }
        }
    }

    // Wallpaper view (Back on Home) fades the layout out, leaving just the background.
    val homeAlpha = if (preview) 1f else homeShown(state)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bgTop, colors.bgMid, colors.bgBottom))),
    ) {
        val photo = state.wallpapers.current
        if (settings.bgTide == Background.PHOTO && photo != null) {
            PhotoBackdrop(photo, state.dayness(), state.wallpapers.credit.takeIf { settings.wallCredit }, tint = homeAlpha)
        } else {
            TideBackdrop(colors, motion)
        }

        // Search and settings share the same top-right entry points in every theme.
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 48.dp).graphicsLayer { alpha = homeAlpha },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val downTarget = if (showStrip) stripFirst else chips.requester(currentIndex)
            SearchButton(colors, Modifier.focusProperties { down = downTarget }) { state.overlay = Overlay.SEARCH }
            GearButton(colors, Modifier.focusProperties { down = downTarget }) { state.overlay = Overlay.QUICK }
        }

        Column(Modifier.fillMaxSize().graphicsLayer { alpha = homeAlpha }, horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(36.dp))
            Text(
                state.greetingLine().orEmpty(),
                color = colors.inkSoft, fontSize = 20.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.height(28.dp).graphicsLayer { alpha = greetAlpha.value },
            )
            val clock = clockText(state.now, settings.clock24).removeSuffix(" AM").removeSuffix(" PM")
            Text(clock, color = colors.ink, fontSize = 120.sp, fontWeight = FontWeight.Thin, letterSpacing = (-4).sp)
            Text(
                java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE · MMMM d")).uppercase(),
                color = colors.inkSoft, fontSize = 15.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp,
            )
            // Shared Home headline: understated, like the greeting above the clock.
            // Reserve room for two lines so the rest of Tide never jumps on a text update.
            Box(Modifier.fillMaxWidth().height(60.dp).padding(start = 72.dp, end = 72.dp, top = 12.dp),
                contentAlignment = Alignment.TopCenter) {
                Crossfade(targetState = rememberHeadline(state, preview), animationSpec = tween(600), label = "tideHeadline") { line ->
                    Text(line, color = colors.inkSoft, fontSize = 20.sp, fontWeight = FontWeight.Light,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))

            if (showStrip) {
                SmartStrip(
                    state, colors, stripFirst,
                    Modifier.fillMaxWidth().padding(horizontal = 96.dp)
                        .onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN) { chips.focus(currentIndex) },
                )
                Spacer(Modifier.height(18.dp))
            }

            LazyRow(
                state = chips.state,
                modifier = chips.keys,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            ) {
                itemsIndexed(shown, key = { _, sc -> sc.collection.id }) { i, sc ->
                    Pill(
                        sc.collection.name, colors,
                        selected = sc.collection.id == current?.collection?.id,
                        // Down enters this collection at the app last opened from it (or the first).
                        modifier = chips.item(i).onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN) {
                            dockRow.focus(if (sc.isInputs) 0 else state.entryIndex(sc))
                        },
                        icon = CollectionIcons.of(sc.collection.icon),
                        // Leaving the dock drops the app name so the label shows the collection again.
                        onFocused = { chips.onItemFocused(i); state.selectedCollection = sc.collection.id; state.focusedApp = null },
                    ) { state.selectedCollection = sc.collection.id }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                state.focusedApp?.label ?: current?.collection?.name.orEmpty(),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.glass)
                    .border(1.dp, colors.edge, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    current?.isInputs == true -> {
                        val dock = dockRow
                        LazyRow(
                            state = dock.state,
                            modifier = dock.keys.onKeyDown(KeyEvent.KEYCODE_DPAD_UP) { chips.focus(currentIndex) },
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(state.inputs, key = { _, input -> input.id }) { i, input ->
                                Pill(
                                    input.label, colors, modifier = dock.item(i), icon = CollectionIcons.of("hdmi"),
                                    onFocused = { dock.onItemFocused(i) },
                                ) { state.switchTo(input) }
                            }
                        }
                    }
                    current == null || current.apps.isEmpty() ->
                        Text("Empty. Hold OK on any app to move it here.", color = colors.ink, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                    else -> {
                        val visible = current.apps.map { it.pkg }
                        val dock = dockRow.also { it.paused = state.moving != null }
                        LazyRow(
                            state = dock.state,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            modifier = Modifier.height((76 * settings.cardSize.scale).dp).then(dock.keys)
                                .onKeyDown(KeyEvent.KEYCODE_DPAD_UP, { state.moving == null }) { chips.focus(currentIndex) },
                        ) {
                            itemsIndexed(current.apps, key = { _, app -> app.pkg }) { i, app ->
                                DockIcon(state, app, colors, visible, settings.cardSize.scale, dock.item(i)) { dock.onItemFocused(i) }
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
    // Not while the Last night recap holds focus; closing it brings focus to the first row.
    LaunchedEffect(state.overlay) {
        if (state.overlay == Overlay.NONE && shown.isNotEmpty() && !state.popupShowing) {
            delay(80) // let the closing window hand focus back first
            chips.state.scrollToItem(currentIndex)
            runCatching { chips.requester(currentIndex).requestFocus() }
        }
    }

    LaunchedEffect(shown.isNotEmpty(), state.homePresses) {
        if (shown.isNotEmpty() && !state.popupShowing) {
            chips.state.scrollToItem(0)
            runCatching { chips.requester(0).requestFocus() }
        }
    }
}

/** Lets the greeting fade run even with the TV's system animations switched off. */
private object RealSpeed : MotionDurationScale {
    override val scaleFactor = 1f
}

@Composable
private fun DockIcon(
    state: HavenState,
    app: AppEntry,
    colors: ThemeColors,
    visible: List<String>,
    scale: Float,
    modifier: Modifier,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val moving = state.moving == app.pkg
    val size by animateFloatAsState((if (focused) 60f else 48f) * scale, label = "dockSize")
    Box(
        modifier
            .size(size.dp)
            .shadow(if (focused) 16.dp else 0.dp, CircleShape, ambientColor = app.color, spotColor = app.color)
            .clip(CircleShape)
            .background(app.color.copy(alpha = 0.55f))
            .border(if (focused || moving) 3.dp else 1.dp, if (moving) colors.accent else if (focused) Color.White else colors.edge, CircleShape)
            .moveKeys(state, app.pkg, visible)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) { state.focusedApp = app; onFocused() } }
            .tvClickable(onLongClick = { state.menuFor = app }) { state.launch(app) },
        contentAlignment = Alignment.Center,
    ) {
        Image(app.icon, app.label, Modifier.size((size * 0.62f).dp))
    }
}

/** Sun or moon, its shimmer on the water, and three rolling waves. */
@Composable
fun TideBackdrop(colors: ThemeColors, motion: Motion, modifier: Modifier = Modifier.fillMaxSize()) {
    val seconds = rememberAmbientSeconds(motion)
    val still = motion == Motion.OFF

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val t = seconds.value
        // Each wave layer rolls at its own pace; the shimmer breathes every 3.5 s.
        val p1 = (t / 18f) % 1f
        val p2 = (t / 26f) % 1f
        val p3 = (t / 36f) % 1f
        val shimmer = 0.575f + 0.225f * sin(t * 2 * PI.toFloat() / 3.5f)
        val orb = Offset(w * 0.73f, h * 0.2f)
        drawCircle(Brush.radialGradient(listOf(colors.glow, Color.Transparent), orb, w * 0.12f), w * 0.12f, orb)
        drawCircle(colors.accent, w * 0.04f, orb)

        drawWave(colors.wave3, h * 0.55f, h * 0.025f, if (still) 0f else p3)
        drawWave(colors.wave2, h * 0.64f, h * 0.03f, if (still) 0f else p2)
        // Shimmer stays in the band between the horizon and the collection chips.
        for (i in 0 until 4) {
            val sw = w * (0.05f + i * 0.016f)
            drawRoundRect(
                colors.accent.copy(alpha = if (still) 0.5f else shimmer),
                topLeft = Offset(orb.x - sw / 2 - i * 4f, h * (0.565f + i * 0.014f)),
                size = androidx.compose.ui.geometry.Size(sw, 5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
            )
        }
        drawWave(colors.wave1, h * 0.76f, h * 0.022f, if (still) 0f else p1)
    }
}

private fun DrawScope.drawWave(color: Color, baseY: Float, amp: Float, phase: Float) {
    val w = size.width
    val path = Path().apply {
        moveTo(0f, size.height)
        lineTo(0f, baseY)
        var x = 0f
        while (x <= w) {
            val y = baseY + amp * sin((x / w * 2 * PI + phase * 2 * PI).toFloat())
            lineTo(x, y)
            x += 12f
        }
        lineTo(w, size.height)
        close()
    }
    drawPath(path, color)
}
