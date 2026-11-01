package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeSearchProviderTest {
    private val smartTube = YouTubeSearchProvider.SMARTTUBE
    private val youTube = YouTubeSearchProvider.YOUTUBE

    @Test fun automaticPrefersSmartTubeWhenBothEnabled() {
        assertEquals(smartTube, chooseYouTubeSearchProvider(YouTubeSearchPreference.AUTO, setOf(smartTube, youTube)))
    }

    @Test fun automaticUsesYouTubeWhenSmartTubeIsNotAvailable() {
        assertEquals(youTube, chooseYouTubeSearchProvider(YouTubeSearchPreference.AUTO, setOf(youTube)))
    }

    @Test fun explicitYouTubePreferenceWinsWhenBothEnabled() {
        assertEquals(youTube, chooseYouTubeSearchProvider(YouTubeSearchPreference.YOUTUBE, setOf(smartTube, youTube)))
    }

    @Test fun explicitPreferenceFallsBackWhenItsAppIsUnavailable() {
        assertEquals(smartTube, chooseYouTubeSearchProvider(YouTubeSearchPreference.YOUTUBE, setOf(smartTube)))
        assertEquals(youTube, chooseYouTubeSearchProvider(YouTubeSearchPreference.SMARTTUBE, setOf(youTube)))
    }

    @Test fun noEnabledProviderHidesSearchAction() {
        for (preference in YouTubeSearchPreference.entries) {
            assertNull(chooseYouTubeSearchProvider(preference, emptySet()))
        }
    }
}
