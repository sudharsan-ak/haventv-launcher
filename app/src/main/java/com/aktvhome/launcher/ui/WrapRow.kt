package com.aktvhome.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A horizontal row whose ends join up: a single press past the last item lands on the
 * first, and the other way round. Holding the key stops at the end instead of looping.
 *
 * Usage: give the LazyRow [state] and [keys], and each item [item] (index) as its modifier.
 */
class WrapRow internal constructor(val state: LazyListState, private val scope: CoroutineScope) {

    private val requesters = mutableMapOf<Int, FocusRequester>()
    private var focused = -1
    var count = 0
        internal set
    /** While true (an app is being moved), left/right belong to the move instead. */
    var paused = false

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    /** Modifier for item [index]: tracks focus position and lets the row move focus to it. */
    fun item(index: Int): Modifier = Modifier.focusRequester(requester(index))

    fun onItemFocused(index: Int) {
        focused = index
    }

    /** Scrolls item [index] into view and focuses it (a lazy row can't focus what isn't composed). */
    fun focus(index: Int) {
        if (count == 0) return
        val i = index.coerceIn(0, count - 1)
        scope.launch {
            state.scrollToItem(i)
            runCatching { requester(i).requestFocus() }
        }
    }

    /** Key handling for the whole row. */
    val keys: Modifier = Modifier.onPreviewKeyEvent { e ->
        val code = e.nativeKeyEvent.keyCode
        if (paused) return@onPreviewKeyEvent false
        if (code != KeyEvent.KEYCODE_DPAD_RIGHT && code != KeyEvent.KEYCODE_DPAD_LEFT) return@onPreviewKeyEvent false
        val atEnd = (code == KeyEvent.KEYCODE_DPAD_RIGHT && focused == count - 1) ||
            (code == KeyEvent.KEYCODE_DPAD_LEFT && focused == 0)
        if (!atEnd || count < 2) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
            val target = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 0 else count - 1
            // The slide runs at real speed even when the TV's animations are switched off.
            scope.launch(RealSpeed) {
                state.animateScrollToItem(target)
                runCatching { requester(target).requestFocus() }
            }
        }
        // Held keys (repeats) are swallowed here, so the row stops at its end.
        true
    }

    private object RealSpeed : MotionDurationScale {
        override val scaleFactor = 1f
    }
}

/**
 * Apps laid out in lines of [columns]. Right from the end of a line goes to the start of the next,
 * Left from the start of a line to the end of the one above; past the very last app a single press
 * wraps to the first (held keys stop there). Up/down between lines is plain geometry.
 */
class AppGrid internal constructor() {

    private val requesters = mutableMapOf<Int, FocusRequester>()
    private var focused = -1
    var count = 0
        internal set
    var columns = 5
    /** While true (an app is being moved), left/right belong to the move instead. */
    var paused = false

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    fun item(index: Int): Modifier = Modifier.focusRequester(requester(index))

    fun onItemFocused(index: Int) {
        focused = index
    }

    /** True while focus is on the first line, so Up leaves the grid. */
    val inTopRow: Boolean get() = focused in 0 until columns

    fun focus(index: Int) {
        if (count == 0) return
        runCatching { requester(index.coerceIn(0, count - 1)).requestFocus() }
    }

    val keys: Modifier = Modifier.onPreviewKeyEvent { e ->
        val code = e.nativeKeyEvent.keyCode
        if (paused || count < 2 || focused !in 0 until count) return@onPreviewKeyEvent false
        val (target, wraps) = when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                focused == count - 1 -> 0 to true
                (focused + 1) % columns == 0 -> focused + 1 to false
                else -> return@onPreviewKeyEvent false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                focused == 0 -> count - 1 to true
                focused % columns == 0 -> focused - 1 to false
                else -> return@onPreviewKeyEvent false
            }
            else -> return@onPreviewKeyEvent false
        }
        // Held keys run through the lines but stop at the very end instead of looping.
        if (e.type == KeyEventType.KeyDown && !(wraps && e.nativeKeyEvent.repeatCount > 0)) {
            runCatching { requester(target).requestFocus() }
        }
        true
    }
}

@Composable
fun rememberAppGrid(count: Int, columns: Int): AppGrid =
    remember { AppGrid() }.also { it.count = count; it.columns = columns }

/**
 * Runs [action] on a single press of [keyCode] and consumes it, instead of letting Android pick
 * whatever sits geometrically nearest. Used to move between a collection and its apps.
 */
fun Modifier.onKeyDown(keyCode: Int, enabled: () -> Boolean = { true }, action: () -> Unit): Modifier =
    onPreviewKeyEvent { e ->
        if (e.nativeKeyEvent.keyCode != keyCode || !enabled()) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown) action()
        true
    }

@Composable
fun rememberWrapRow(count: Int): WrapRow {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    return remember { WrapRow(state, scope) }.also { it.count = count }
}
