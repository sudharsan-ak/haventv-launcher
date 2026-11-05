package com.aktvhome.launcher.recent

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSwitcherNavigationTest {
    @Test fun moveBetweenCardsWithoutWrapping() {
        assertEquals(0, RecentSwitcherNavigation.move(0, -1, 4))
        assertEquals(1, RecentSwitcherNavigation.move(0, 1, 4))
        assertEquals(3, RecentSwitcherNavigation.move(3, 1, 4))
        assertEquals(0, RecentSwitcherNavigation.move(0, 1, 0))
    }

    @Test fun hidingSelectedCardKeepsRemainingSelectionValid() {
        assertEquals(2, RecentSwitcherNavigation.afterHide(3, 3))
        assertEquals(1, RecentSwitcherNavigation.afterHide(1, 3))
        assertEquals(0, RecentSwitcherNavigation.afterHide(0, 0))
    }
}
