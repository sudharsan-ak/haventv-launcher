package com.aktvhome.launcher.recent

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPlaybackRulesTest {
    @Test fun preservesProgressOnStoppedSessionReset() {
        assertEquals(55_000L, RecentPlaybackRules.retainPosition(0L, 55_000L, true))
    }
    @Test fun preservesProgressWhenPositionUnknown() {
        assertEquals(55_000L, RecentPlaybackRules.retainPosition(-1L, 55_000L, false))
    }
    @Test fun realSeekToStartWhilePlayingIsNotIgnored() {
        assertEquals(0L, RecentPlaybackRules.retainPosition(0L, 55_000L, false))
    }
    @Test fun usesNewerValidPosition() {
        assertEquals(89_000L, RecentPlaybackRules.retainPosition(89_000L, 55_000L, false))
    }
}
