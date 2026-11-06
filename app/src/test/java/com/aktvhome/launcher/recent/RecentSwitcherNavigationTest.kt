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

    @Test fun swipingLeftMovesForwardAndRightMovesBack() {
        assertEquals(1, RecentSwitcherNavigation.swipeStep(-85f, 68f))
        assertEquals(-1, RecentSwitcherNavigation.swipeStep(85f, 68f))
        assertEquals(0, RecentSwitcherNavigation.swipeStep(20f, 68f))
        assertEquals(0, RecentSwitcherNavigation.swipeStep(-67.9f, 68f))
        assertEquals(0, RecentSwitcherNavigation.swipeStep(Float.NaN, 68f))
    }

    @Test fun remoteCanFocusBottomActionsOrReturnToCards() {
        assertEquals(2, RecentSwitcherNavigation.actionFocus(0, 1))
        assertEquals(1, RecentSwitcherNavigation.actionFocus(2, -1))
        assertEquals(0, RecentSwitcherNavigation.actionFocus(2, 0))
    }
}
