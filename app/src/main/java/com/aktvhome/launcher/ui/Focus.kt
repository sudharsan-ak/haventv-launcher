package com.aktvhome.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private fun isSelectKey(code: Int) =
    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * Remote-friendly click: OK fires on release, holding OK fires [onLongClick] once.
 * A release only counts if the press started here, so the release that follows a
 * long-press never triggers whatever the menu focused next.
 */
fun Modifier.tvClickable(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    this
        .onPreviewKeyEvent { e ->
            val native = e.nativeKeyEvent
            if (!isSelectKey(native.keyCode)) return@onPreviewKeyEvent false
            when (e.type) {
                KeyEventType.KeyDown -> {
                    if (native.repeatCount == 0) {
                        pressed = true
                        longFired = false
                    } else if (native.repeatCount == 1 && pressed && onLongClick != null) {
                        longFired = true
                        onLongClick()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (pressed && !longFired) onClick()
                    pressed = false
                    longFired = false
                    true
                }
                else -> false
            }
        }
        .focusable()
}
