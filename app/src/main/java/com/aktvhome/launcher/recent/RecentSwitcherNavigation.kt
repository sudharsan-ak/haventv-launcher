package com.aktvhome.launcher.recent

/** Pure D-pad and swipe rules, shared with unit tests. */
internal object RecentSwitcherNavigation {
    fun move(current: Int, direction: Int, count: Int): Int =
        if (count <= 0) 0 else (current + direction).coerceIn(0, count - 1)

    fun afterHide(current: Int, remaining: Int): Int =
        if (remaining <= 0) 0 else current.coerceIn(0, remaining - 1)

    /** Dragging left advances; dragging right moves backward. Small drags snap back. */
    fun swipeStep(distancePx: Float, thresholdPx: Float): Int = when {
        thresholdPx <= 0f || !distancePx.isFinite() -> 0
        distancePx <= -thresholdPx -> 1
        distancePx >= thresholdPx -> -1
        else -> 0
    }

    /** D-pad Down enters the bottom controls; Back/Up return to the cards. */
    fun actionFocus(current: Int, key: Int): Int = when (key) {
        -1 -> 1
        1 -> 2
        0 -> 0
        else -> current.coerceIn(0, 2)
    }
}
