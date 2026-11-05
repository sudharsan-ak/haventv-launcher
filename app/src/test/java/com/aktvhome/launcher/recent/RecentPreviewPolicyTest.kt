package com.aktvhome.launcher.recent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentPreviewPolicyTest {
    @Test fun foregroundAttemptIsThrottled() {
        assertFalse(RecentPreviewPolicy.isDue(true, 7_999))
        assertTrue(RecentPreviewPolicy.isDue(true, 8_000))
    }

    @Test fun refreshRequiresSixtySeconds() {
        assertFalse(RecentPreviewPolicy.isDue(false, 59_999))
        assertTrue(RecentPreviewPolicy.isDue(false, 60_000))
    }

    @Test fun rejectsAlmostBlackSamples() {
        assertTrue(RecentPreviewPolicy.isAlmostBlack(List(60) { 0xff050506.toInt() }))
        assertFalse(RecentPreviewPolicy.isAlmostBlack(List(60) { 0xff2e3b51.toInt() }))
        assertFalse(RecentPreviewPolicy.isAlmostBlack(emptyList()))
    }
}
