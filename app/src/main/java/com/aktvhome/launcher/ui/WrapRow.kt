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

@Composable
fun rememberWrapRow(count: Int): WrapRow {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    return remember { WrapRow(state, scope) }.also { it.count = count }
}
