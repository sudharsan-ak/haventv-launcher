package com.aktvhome.launcher.recent

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentWatchNextRankingTest {
    @Test fun prioritizeResumableWatchNextAboveGeneralPrograms() {
        assertEquals(0, RecentWatchNext.priority(true, 15_000L, false))
        assertEquals(1, RecentWatchNext.priority(false, 15_000L, false))
        assertEquals(2, RecentWatchNext.priority(false, -1L, true))
        assertEquals(3, RecentWatchNext.priority(true, -1L, false))
        assertEquals(4, RecentWatchNext.priority(false, -1L, false))
    }
}
