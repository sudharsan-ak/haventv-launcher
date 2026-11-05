package com.aktvhome.launcher.recent

/** Conservative limits for automatic screenshot attempts on a TV. */
internal object RecentPreviewPolicy {
    const val MAX_CACHED_APPS = 12
    const val FIRST_CAPTURE_DELAY_MS = 1_500L
    const val REFRESH_DELAY_MS = 1_200L
    private const val REENTRY_GAP_MS = 8_000L
    private const val REFRESH_GAP_MS = 60_000L

    fun isDue(reentered: Boolean, elapsedSinceAttemptMs: Long): Boolean =
        elapsedSinceAttemptMs >= if (reentered) REENTRY_GAP_MS else REFRESH_GAP_MS

    /** Reject near-black frames, a common outcome for protected playback. */
    fun isAlmostBlack(samples: List<Int>): Boolean =
        samples.isNotEmpty() && samples.count { pixel ->
            ((pixel ushr 16) and 255) <= 18 &&
                ((pixel ushr 8) and 255) <= 18 &&
                (pixel and 255) <= 18
        } >= samples.size - 1
}
