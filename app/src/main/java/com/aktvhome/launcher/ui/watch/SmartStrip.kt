package com.aktvhome.launcher.ui.watch

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.NbaRotation
import com.aktvhome.launcher.data.PlaybackKind
import com.aktvhome.launcher.data.PlayingMedia
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** True when at least one Watch Now section is switched on, so the strip has something to show. */
fun HavenState.stripVisible(): Boolean = settingsStore.settings.let { it.nbaCardEnabled() || it.watchContinue || it.watchLive || (it.showNowPlaying && nowPlaying.sessions.isNotEmpty()) }

/**
 * The slim Watch Now strip on Home: Tonight (NBA), Continue, Live TV, and See more.
 * [first] takes focus when the strip is entered; [modifier] carries the theme's key handling.
 */
@Composable
fun SmartStrip(state: HavenState, colors: ThemeColors, first: FocusRequester, modifier: Modifier = Modifier) {
    val s = state.settingsStore.settings
    val w = state.watchNow
    var firstUsed = false
    fun claim(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
    val nbaLogo: @Composable () -> Unit = { NetImage(w, Nba.LEAGUE_LOGO, 11.dp) }
    val eligible = w.homeRotationGames()
    val ids = eligible.map { it.espnId }
    var rotationId by remember { mutableStateOf<String?>(null) }
    var nbaFocused by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rotate = s.nbaCardEnabled() && s.rotateNba && ids.size > 1

    // Card animation and the timer are Home-only. ESPN updates are handled by WatchNow.
    // Use stable ESPN identifiers to avoid resetting the timer every time a score changes.
    LaunchedEffect(lifecycle, rotate, ids, nbaFocused, s.nbaRotationInterval, state.overlay, state.wallpaperView) {
        if (!rotate || nbaFocused || state.overlay != Overlay.NONE || state.wallpaperView) return@LaunchedEffect
        if (rotationId !in ids) rotationId = ids.first()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val index = ids.indexOf(rotationId).takeIf { it >= 0 } ?: 0
                delay(NbaRotation.displayMillis(index, ids.size, s.nbaRotationInterval.millis))
                rotationId = NbaRotation.nextId(ids, rotationId)
            }
        }
    }

    Row(modifier.height(68.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      for (section in s.watchOrder) when (section) {
        "nba" -> if (s.nbaCardEnabled()) {
            val g = if (s.rotateNba) eligible.firstOrNull { it.espnId == rotationId } ?: eligible.firstOrNull() ?: w.featured() else w.featured()
            val fav = s.favoriteTeam
            when {
                g != null -> StripCard(
                    (if (g.involves(fav)) Nba.teamName(fav).substringAfterLast(' ').uppercase() + " · TODAY" else "TONIGHT") +
                        (if (rotate) " · ${ids.indexOf(g.espnId) + 1}/${ids.size}" else ""),
                    matchup(g), g.status + (g.network?.let { " · $it" } ?: ""), g.state == GameState.LIVE,
                    colors, claim().weight(1.25f), nbaLogo,
                    onFocusState = { nbaFocused = it },
                ) { state.openGame(g) }
                w.games[w.today()] == null && !w.nbaError -> StripCard("TONIGHT", "Loading games…", "ESPN", false, colors, claim().weight(1.25f), nbaLogo) {
                    state.overlay = Overlay.NBA
                }
                else -> {
                    val next = w.nextFavorite()
                    StripCard(
                        "TONIGHT", if (w.nbaError) "Scores unavailable" else "No NBA games today",
                        next?.let { "Next: ${shortDate(it)} · ${matchup(it)}" } ?: "Check other days in Watch Now",
                        false, colors, claim().weight(1.25f), nbaLogo,
                    ) { state.overlay = Overlay.NBA }
                }
            }
        }
        "continue" -> if (s.watchContinue || (s.showNowPlaying && state.nowPlaying.sessions.isNotEmpty())) {
            val playing = if (s.showNowPlaying) state.nowPlaying.sessions else emptyList()
            if (playing.isNotEmpty()) {
                NowPlayingCard(state, playing, colors, claim().weight(1f))
            } else {
                val item = w.continueWatching.firstOrNull()
                StripCard(
                    "CONTINUE", item?.title ?: "Nothing to continue",
                    item?.let { listOf(it.detail, state.apps[it.pkg]?.label.orEmpty(), it.progress?.let { p -> "${(p * 100).toInt()}% watched" }.orEmpty()).filter { s -> s.isNotBlank() }.joinToString(" · ") }
                        ?: "Apps that share progress show up here",
                    false, colors, claim().weight(1f),
                ) { state.overlay = Overlay.CONTINUE }
            }
        }
        "live" -> if (s.watchLive) {
            val groups = w.live
            StripCard(
                "LIVE TV", groups.take(2).joinToString(" + ") { state.apps[it.pkg]?.label ?: it.provider }.ifBlank { "No live channels" },
                groups.firstOrNull()?.items?.take(3)?.joinToString(", ") { it.title } ?: "Live TV apps that share channels show up here",
                groups.any { g -> g.items.any { it.live } }, colors, claim().weight(1f),
            ) { state.overlay = Overlay.LIVE }
        }
      }
        SeeMore(colors, claim()) { state.overlay = Overlay.WATCH }
    }
}


/**
 * Compact Home carousel. D-pad Left/Right changes the media session until it reaches an
 * endpoint; at the endpoint we return false so Compose moves to the adjacent Home card.
 * The arrows are pointer/tap controls for a phone, mouse or PC and do not steal D-pad focus.
 */
@Composable
private fun RowScope.NowPlayingCard(
    state: HavenState,
    sessions: List<PlayingMedia>,
    colors: ThemeColors,
    modifier: Modifier,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val index = sessions.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
    val media = sessions[index]
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val ink = if (focused) colors.pillInk else colors.ink
    val next: (Int) -> Unit = { delta ->
        val target = index + delta
        if (target in sessions.indices) selectedId = sessions[target].id
    }

    Row(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val delta = when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        else -> 0
                    }
                    when {
                        delta == 0 -> false
                        // A held remote key must not skip through the carousel or escape it.
                        // Releasing and pressing again is required for the next step.
                        event.nativeKeyEvent.repeatCount != 0 -> true
                        index + delta in sessions.indices -> { next(delta); true }
                        else -> false
                    }
                }
            }
            .tvClickable { state.openPlayingMedia(media) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val app = state.apps[media.pkg]
        val image = media.artwork ?: app?.icon
        if (image != null) {
            Image(
                image, media.title,
                Modifier.size(width = 54.dp, height = 39.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(32.dp), tint = ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                (if (media.kind == PlaybackKind.PAUSED) "PAUSED" else "NOW PLAYING") +
                    (if (sessions.size > 1) " · ${index + 1}/${sessions.size}" else ""),
                color = ink.copy(alpha = 0.72f), fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, maxLines = 1,
            )
            // The media repository refreshes every few seconds. Anchor the marquee to the
            // visible app/title, not the framework session token, so polling cannot restart
            // a long title midway through its scroll.
            key(media.pkg, media.title) {
                // This bounded viewport is important: the marquee measures its child using
                // unbounded width, while only this box's width is visible on the Home card.
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        media.title,
                        modifier = Modifier.basicMarquee(
                            iterations = if (state.overlay == Overlay.NONE && !state.wallpaperView) Int.MAX_VALUE else 0,
                            spacing = MarqueeSpacing(12.dp),
                            initialDelayMillis = 1_000,
                            repeatDelayMillis = 1_400,
                            velocity = 48.dp,
                        ),
                        color = ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                app?.icon?.let { icon ->
                    Image(icon, null, Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)))
                }
                Text(
                    (app?.label ?: media.pkg.substringAfterLast('.')) +
                        " · " + (if (media.kind == PlaybackKind.PAUSED) "Paused" else if (media.kind == PlaybackKind.BUFFERING) "Buffering" else "Playing"),
                    color = ink.copy(alpha = 0.75f), fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (sessions.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MediaArrow("‹", index > 0, colors, ink) { next(-1) }
                MediaArrow("›", index < sessions.lastIndex, colors, ink) { next(1) }
            }
        }
    }
}

@Composable
private fun MediaArrow(
    symbol: String,
    enabled: Boolean,
    colors: ThemeColors,
    ink: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 23.dp, height = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.glass.copy(alpha = 0.35f))
            .then(if (enabled) Modifier.pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = ink.copy(alpha = if (enabled) 0.95f else 0.3f),
            fontSize = 23.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RowScope.StripCard(
    kicker: String,
    main: String,
    sub: String,
    live: Boolean,
    colors: ThemeColors,
    modifier: Modifier,
    /** Small mark before the kicker (the NBA logo). */
    badge: (@Composable () -> Unit)? = null,
    onFocusState: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val ink = if (focused) colors.pillInk else colors.ink
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusState(focused)
                }
            }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val presentation = StripPresentation(kicker, main, sub, live)
        Crossfade(targetState = presentation, animationSpec = tween(550), label = "Home strip content") { value ->
            Column(verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    badge?.invoke()
                    Text(value.kicker, color = ink.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
                }
                Text(value.main, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (value.live) Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE53935)))
                    Text(value.sub, color = ink.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private data class StripPresentation(val kicker: String, val main: String, val sub: String, val live: Boolean)

@Composable
private fun SeeMore(colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    // Same glass as the cards beside it; only focus makes it solid.
    Column(
        modifier
            .width(118.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val ink = if (focused) colors.pillInk else colors.ink
        Text("WATCH NOW", color = ink.copy(alpha = 0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text("See more", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** "GS 105 · LAL 127" once it's started, "MEM @ GS" before. */
fun matchup(g: Game): String =
    if (g.state == GameState.PRE) "${g.away.abbr} @ ${g.home.abbr}"
    else "${g.away.abbr} ${g.away.score} · ${g.home.abbr} ${g.home.score}"

private val shortFmt = DateTimeFormatter.ofPattern("EEE MMM d")

fun shortDate(g: Game): String = g.start.atZone(ZoneId.systemDefault()).format(shortFmt)
