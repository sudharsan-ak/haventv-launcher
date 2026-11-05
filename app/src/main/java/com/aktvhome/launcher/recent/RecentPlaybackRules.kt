package com.aktvhome.launcher.recent

/** Pure helper for protecting the last known resume position during player shutdown. */
internal object RecentPlaybackRules {
    fun retainPosition(incoming: Long, previous: Long, resetting: Boolean): Long =
        if ((incoming < 0 || (resetting && incoming <= 1_000L)) && previous > 1_000L)
            previous else incoming
}
