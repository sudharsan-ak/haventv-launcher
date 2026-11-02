package com.aktvhome.launcher.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aktvhome.launcher.data.Motion
import com.aktvhome.launcher.ui.theme.ThemeColors

/**
 * Only a fresh Down key press at the bottom of the apps may collapse the dock.
 * A held key arriving from rows above must not dismiss the entire apps section.
 * Moving an app must take precedence: callers supply enabled = false while moving.
 */
fun Modifier.collapseOnLastDown(enabled: () -> Boolean, collapse: () -> Unit): Modifier =
    onPreviewKeyEvent { e ->
        if (e.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_DOWN || !enabled())
            return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) collapse()
        // Also swallow auto-repeated Down presses at the bottom.
        true
    }

/** The whole collections/apps area slides off screen, without changing wallpaper rendering. */
@Composable
fun SlidingApps(visible: Boolean, motion: Motion, content: @Composable () -> Unit) {
    val animate = motion != Motion.OFF
    AnimatedVisibility(
        visible = visible,
        enter = if (animate) slideInVertically(
            initialOffsetY = { it }, animationSpec = tween(350),
        ) + fadeIn(animationSpec = tween(300)) else EnterTransition.None,
        exit = if (animate) slideOutVertically(
            targetOffsetY = { it }, animationSpec = tween(350),
        ) + fadeOut(animationSpec = tween(300)) else ExitTransition.None,
    ) {
        content()
    }
}

/**
 * Small focusable line shown at the bottom of both themes after collapsing.
 * Up or OK expands; its focus ring is visible only when using the remote.
 */
@Composable
fun CollapsedAppsHandle(
    colors: ThemeColors,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    expand: () -> Unit,
    leaveToUpperControls: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .focusRequester(focusRequester)
            .width(76.dp)
            .height(25.dp)
            .clip(shape)
            .background(colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) expand()
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        // Leave the handle without expanding the apps or invoking
                        // Android's ordinary Home/Back wallpaper-view behavior.
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0)
                            leaveToUpperControls()
                        true
                    }
                    else -> false
                }
            }
            .semantics { contentDescription = "Expand app collections" }
            .tvClickable(onClick = expand),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(47.dp).height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(if (focused) colors.accent else colors.ink.copy(alpha = 0.80f)),
        )
    }
}
