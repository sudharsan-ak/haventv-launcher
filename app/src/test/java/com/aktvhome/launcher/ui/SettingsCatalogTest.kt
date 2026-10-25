package com.aktvhome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test fun settingIdsAreUnique() {
        assertEquals(HAVEN_SETTINGS.size, HAVEN_SETTINGS.map { it.id }.distinct().size)
    }

    @Test fun everySettingsPageHasSearchEntries() {
        SettingsDestination.entries.forEach { destination ->
            assertTrue(destination.name, HAVEN_SETTINGS.any { it.destination == destination })
        }
    }

    @Test fun bannerTimeHasAnExactFocusTarget() {
        val banner = HAVEN_SETTINGS.firstOrNull { it.id == "banner_time" }
        assertNotNull(banner)
        assertEquals(SettingsDestination.NBA_ALERTS, banner?.destination)
        assertEquals("Banner time", banner?.focusTitle)
    }
}
