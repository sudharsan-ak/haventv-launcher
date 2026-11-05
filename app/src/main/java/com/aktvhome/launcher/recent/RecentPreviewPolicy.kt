package com.aktvhome.launcher.recent

/** Strictly bounded, event-driven preview capture. No periodic screenshots. */
internal object RecentPreviewPolicy {
    const val MAX_CACHED_APPS = 12
    const val FIRST_CAPTURE_DELAY_MS = 1_500L
    const val NAVIGATION_SETTLE_MS = 650L
    const val REENTRY_GAP_MS = 8_000L
    const val NAVIGATION_GAP_MS = 6_000L
    const val GLOBAL_GAP_MS = 1_500L

    fun isDue(reentered: Boolean, elapsedSinceAttemptMs: Long): Boolean =
        elapsedSinceAttemptMs >= if (reentered) REENTRY_GAP_MS else NAVIGATION_GAP_MS

    /** Screens with nearly all sampled pixels black cannot be used as previews. */
    fun isAlmostBlack(samples: List<Int>): Boolean =
        samples.isNotEmpty() && samples.count { pixel ->
            ((pixel ushr 16) and 255) <= 18 &&
                ((pixel ushr 8) and 255) <= 18 &&
                (pixel and 255) <= 18
        } >= samples.size * 95 / 100

    /** Diagnostic only: not proof of DRM; many ordinary UIs have dark regions. */
    fun hasLargeDarkRegion(samples: List<Int>): Boolean =
        samples.isNotEmpty() && !isAlmostBlack(samples) &&
            samples.count { pixel ->
                ((pixel ushr 16) and 255) < 24 &&
                    ((pixel ushr 8) and 255) < 24 &&
                    (pixel and 255) < 24
            } >= samples.size * 55 / 100
}
