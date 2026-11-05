package com.aktvhome.launcher.recent

/** A dark UI alone is not proof of a blocked or unusable screenshot. */
internal enum class RecentPreviewChoice { SCREENSHOT, MEDIA, APP_ARTWORK }

internal object RecentPreviewSelection {
    fun choose(screenshotAvailable: Boolean, mediaAvailable: Boolean): RecentPreviewChoice =
        when {
            screenshotAvailable -> RecentPreviewChoice.SCREENSHOT
            mediaAvailable -> RecentPreviewChoice.MEDIA
            else -> RecentPreviewChoice.APP_ARTWORK
        }
}
