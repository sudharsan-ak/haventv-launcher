package com.aktvhome.launcher.recent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentPreviewPolicyTest {
    @Test fun foregroundAttemptIsThrottled() {
        assertFalse(RecentPreviewPolicy.isDue(true, 7_999))
        assertTrue(RecentPreviewPolicy.isDue(true, 8_000))
    }

    @Test fun navigationRefreshNeedsSixSeconds() {
        assertFalse(RecentPreviewPolicy.isDue(false, 5_999))
        assertTrue(RecentPreviewPolicy.isDue(false, 6_000))
    }

    @Test fun detectAlmostBlackButNotPartiallyBlack() {
        assertTrue(RecentPreviewPolicy.isAlmostBlack(List(100) { 0xff050506.toInt() }))
        assertFalse(RecentPreviewPolicy.isAlmostBlack(List(100) {
            if (it < 70) 0xff050506.toInt() else 0xff9eafc1.toInt()
        }))
        assertFalse(RecentPreviewPolicy.isAlmostBlack(emptyList()))
    }

    @Test fun detectMostlyBlackUiAsPartialNotSecure() {
        val pixels = List(100) {
            if (it < 70) 0xff050506.toInt() else 0xff9eafc1.toInt()
        }
        assertTrue(RecentPreviewPolicy.hasLargeDarkRegion(pixels))
        assertFalse(RecentPreviewPolicy.hasLargeDarkRegion(List(100) { 0xff050506.toInt() }))
    }
}
