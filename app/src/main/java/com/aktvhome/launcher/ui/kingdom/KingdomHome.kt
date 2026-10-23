package com.aktvhome.launcher.ui.kingdom

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.aktvhome.launcher.ui.rememberWrapRow
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private val SIDE = 48.dp

@Composable
fun KingdomHome(state: HavenState, colors: ThemeColors) {
    val settings = state.settingsStore.settings
    val shown = state.shownCollections()
    val current = state.currentCollection(shown)
    val tiles = rememberWrapRow(shown.size)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Collections and apps always show with the full header above them.
    val toTop: () -> Unit = { scope.launch { scroll.scrollTo(0) } }

    // The background glow leans toward the focused app's own color.
    val glow by animateColorAsState(
        state.focusedApp?.color?.copy(alpha = colors.glow.alpha) ?: colors.glow, tween(900), label = "glow",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bgTop, colors.bgMid, colors.bgBottom))),
    ) {
        val photo = state.wallpapers.current
        if (settings.bgKingdom == Background.PHOTO && photo != null) {
            PhotoBackdrop(photo, state.dayness(), state.wallpapers.credit.takeIf { settings.wallCredit })
        } else {
            KingdomBackdrop(colors, glow, settings.motion, Modifier.fillMaxSize())
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = 44.dp, bottom = 28.dp),
        ) {
            val side = Modifier.padding(horizontal = SIDE)
            Row(side, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(clockText(state.now, settings.clock24), color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(16.dp))
                GearButton(colors) { state.overlay = Overlay.QUICK }
            }

            Spacer(Modifier.height(26.dp))
            state.greetingLine()?.let { Text(it, color = colors.inkSoft, fontSize = 15.sp, modifier = side) }
            Text(
                "What are we watching?", color = colors.ink, fontSize = 40.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = side,
            )

            Spacer(Modifier.height(22.dp))
            LazyRow(
                state = tiles.state,
                modifier = tiles.keys,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 10.dp, horizontal = SIDE),
            ) {
                itemsIndexed(shown, key = { _, sc -> sc.collection.id }) { i, sc ->
                    CollectionTile(
                        sc, colors, count = if (sc.isInputs) state.inputs.size else sc.apps.size,
                        selected = sc.collection.id == current?.collection?.id,
                        modifier = tiles.item(i),
                        onFocused = {
                            tiles.onItemFocused(i)
                            state.selectedCollection = sc.collection.id
                            toTop()
                        },
                    )
                }
            }

            if (current != null) {
                Spacer(Modifier.height(16.dp))
                Text(current.collection.name, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = side)
                Spacer(Modifier.height(6.dp))
                when {
                    current.isInputs -> {
                        val row = rememberWrapRow(state.inputs.size)
                        LazyRow(
                            state = row.state,
                            modifier = row.keys,
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
                        val row = rememberWrapRow(current.apps.size).also { it.paused = state.moving != null }
                        LazyRow(
                            state = row.state,
                            modifier = row.keys,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 12.dp, horizontal = SIDE),
                        ) {
                            itemsIndexed(current.apps, key = { _, app -> app.pkg }) { i, app ->
                                AppCard(
                                    app, colors, width = settings.cardSize.width.dp,
                                    moving = state.moving == app.pkg,
                                    modifier = row.item(i).moveKeys(state, app.pkg, visible),
                                    onFocused = { row.onItemFocused(i); state.focusedApp = app; toTop() },
                                    onClick = { state.launch(app) },
                                    onLongClick = { state.menuFor = app },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    AppMenuOverlays(state, colors)

    LaunchedEffect(shown.isNotEmpty(), state.homePresses) {
        if (shown.isNotEmpty()) {
            scroll.scrollTo(0)
            tiles.state.scrollToItem(0)
            runCatching { tiles.requester(0).requestFocus() }
        }
    }
}

@Composable
private fun CollectionTile(
    sc: ShownCollection,
    colors: ThemeColors,
    count: Int,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tileScale")
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .width(150.dp)
            .height(96.dp)
            .scale(scale)
            .clip(shape)
            // A shadow under translucent glass shows through as dark patches, so focus brightens the glass instead.
            .background(if (focused) colors.bgTop.copy(alpha = 0.92f) else colors.glass)
            .border(if (focused) 3.dp else 2.dp, if (focused || selected) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onClick = onFocused)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        IconBadge(sc.collection.icon, colors, size = 30.dp, tint = if (focused || selected) colors.accent else colors.ink)
        Column {
            Text(sc.collection.name, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                if (sc.isInputs) "$count inputs" else "$count apps",
                color = colors.inkSoft, fontSize = 11.sp,
            )
        }
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
