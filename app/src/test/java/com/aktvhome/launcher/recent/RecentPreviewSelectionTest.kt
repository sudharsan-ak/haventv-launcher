package com.aktvhome.launcher.recent

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPreviewSelectionTest {
    @Test fun validDarkTvScreenshotBeatsMediaArtwork() {
        assertEquals(RecentPreviewChoice.SCREENSHOT,
            RecentPreviewSelection.choose(screenshotAvailable = true, mediaAvailable = true))
    }

    @Test fun mediaIsFallbackWhenScreenshotMissing() {
        assertEquals(RecentPreviewChoice.MEDIA,
            RecentPreviewSelection.choose(screenshotAvailable = false, mediaAvailable = true))
    }

    @Test fun appBannerIsLastFallback() {
        assertEquals(RecentPreviewChoice.APP_ARTWORK,
            RecentPreviewSelection.choose(screenshotAvailable = false, mediaAvailable = false))
    }

    @Test fun screenshotStillWinsWithoutMedia() {
        assertEquals(RecentPreviewChoice.SCREENSHOT,
            RecentPreviewSelection.choose(screenshotAvailable = true, mediaAvailable = false))
    }
}
