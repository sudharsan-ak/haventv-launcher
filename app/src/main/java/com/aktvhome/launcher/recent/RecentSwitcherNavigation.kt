package com.aktvhome.launcher.recent

/** Pure navigation rules shared by D-pad actions and controller tests. */
internal object RecentSwitcherNavigation {
    fun move(current: Int, direction: Int, count: Int): Int =
        if (count <= 0) 0 else (current + direction).coerceIn(0, count - 1)

    fun afterHide(current: Int, remaining: Int): Int =
        if (remaining <= 0) 0 else current.coerceIn(0, remaining - 1)
}
