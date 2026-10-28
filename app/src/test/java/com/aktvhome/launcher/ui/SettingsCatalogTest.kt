package com.aktvhome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test fun settingIdsAreUnique() {
        assertEquals(HAVEN_SETTINGS.size, HAVEN_SETTINGS.map { it.id }.distinct().size)
    }

    @Test fun everyActiveSettingsPageHasSearchEntries() {
        // NBA_ALERTS remains a backward-compatible routing alias; its controls and search
        // entries now live in NBA_LEAGUE, so it is not a visible standalone settings page.
        SettingsDestination.entries
            .filterNot { it == SettingsDestination.NBA_ALERTS }
            .forEach { destination ->
                assertTrue(destination.name, HAVEN_SETTINGS.any { it.destination == destination })
            }
    }

    @Test fun bannerTimeHasAnExactFocusTarget() {
        val banner = HAVEN_SETTINGS.firstOrNull { it.id == "banner_time" }
        assertNotNull(banner)
        assertEquals(SettingsDestination.NBA_LEAGUE, banner?.destination)
        assertEquals("Watching & alerts · NBA League · Alerts", banner?.path)
        assertEquals("Banner time", banner?.focusTitle)
    }

    @Test fun migratedNbaSettingIdsRetainTheirFocusTargetsAndDestinations() {
        val nba = HAVEN_SETTINGS.associateBy { it.id }
        val expected = mapOf(
            "favorite_team" to ("Overview" to "Favorite team"),
            "teams_followed" to ("Overview" to "Teams you follow"),
            "watch_nba" to ("Home card" to "Show NBA card on Home"),
            "rotate_nba" to ("Home card" to "Rotate NBA games"),
            "nba_rotation_interval" to ("Home card" to "Rotation interval"),
            "alert_cooldown" to ("Alerts" to "Space out other-game alerts"),
            "alert_favorite_tipoff" to ("Alerts" to "favorite:TIP_OFF"),
            "alert_other_close" to ("Alerts" to "other:CLOSE_GAME"),
        )
        expected.forEach { (id, destination) ->
            val spec = nba[id]
            assertNotNull("Missing original search setting ID: $id", spec)
            assertEquals(id, SettingsDestination.NBA_LEAGUE, spec?.destination)
            assertTrue(id, spec?.path?.endsWith("· ${destination.first}") == true)
            assertEquals(id, destination.second, spec?.focusTitle)
        }
    }
}
