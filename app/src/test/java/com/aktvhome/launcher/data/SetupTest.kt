package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTest {

    private val pkg = "com.aktvhome.launcher"

    private fun status(vararg needs: Capability) = Capability.entries.associateWith {
        if (it in needs) CapStatus(CapState.NEEDS_SETUP, "") else CapStatus(CapState.AVAILABLE, "")
    }

    @Test fun neededOnlyCountsWhatSetupCanFix() {
        val s = status(Capability.CEC, Capability.TV_CONTROL, Capability.FILES, Capability.SECURE_SETTINGS)
        assertEquals(listOf(Capability.SECURE_SETTINGS, Capability.FILES), Setup.needed(s))
        assertTrue(Setup.needed(status()).isEmpty())
    }

    @Test fun commandsOnlyForWhatsMissing() {
        assertEquals(
            listOf("appops set $pkg MANAGE_EXTERNAL_STORAGE allow"),
            Setup.commands(pkg, listOf(Capability.FILES), 34),
        )
        assertTrue(Setup.commands(pkg, emptyList(), 34).isEmpty())
    }

    @Test fun homeServiceNeedsTheSecureGrantAndLiftsRestrictionsOn13() {
        val on14 = Setup.commands(pkg, listOf(Capability.HOME_SERVICE), 34)
        assertEquals(
            listOf("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS", "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow"),
            on14,
        )
        assertEquals(listOf("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"), Setup.commands(pkg, listOf(Capability.HOME_SERVICE), 32))
    }

    @Test fun everythingOnAFreshDevice() {
        val all = Setup.commands(pkg, Setup.FIXABLE, 34)
        assertEquals(5, all.size)
        assertEquals("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS", all.first())
        assertTrue("cmd notification allow_listener $pkg/$pkg.HavenMediaListener 0" in all)
        assertTrue(Setup.computerCommands(pkg, 34).all { it.startsWith("adb shell ") })
    }
}
