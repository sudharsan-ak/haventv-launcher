package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupPlanTest {

    private val self = "com.aktvhome.launcher"

    /** A Google TV with Haven, its own home screen, a keyboard, an accessibility service and HDMI inputs. */
    private val tv = GuardContext(
        self = self,
        home = setOf(self, "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith", "com.android.tv.settings"),
        keyboards = setOf("com.google.android.leanback.ime"),
        accessibility = setOf(self, "com.maker.reader"),
        tvInputs = setOf("com.mediatek.tvinput", "com.maker.tuner"),
    )

    private fun pkg(name: String, system: Boolean = true, state: PkgState = PkgState.DEFAULT, sharedUser: String? = null, persistent: Boolean = false) =
        PackageFacts(name, name.substringAfterLast('.'), system, state, persistent = persistent, sharedUserId = sharedUser)

    private fun blocked(name: String) = CleanupPlan.blocked(pkg(name), tv)

    @Test fun coreAndroidIsNeverSwitchedOff() {
        listOf("android", "com.android.systemui", "com.android.tv.settings", "com.google.android.gms", "com.android.providers.media", "com.google.android.webview")
            .forEach { assertEquals(it, GuardReason.CORE, CleanupPlan.blocked(pkg(it), tv.copy(home = emptySet()))) }
        assertEquals(GuardReason.CORE, CleanupPlan.blocked(pkg("com.maker.thing", sharedUser = "android.uid.system"), tv))
        assertEquals(GuardReason.CORE, CleanupPlan.blocked(pkg("com.maker.thing", persistent = true), tv))
        assertEquals(GuardReason.CORE, blocked("com.maker.tv.overlay"))
    }

    @Test fun whateverRunsHomeIsNeverSwitchedOff() {
        assertEquals(GuardReason.HOME, blocked("com.google.android.apps.tv.launcherx"))
        // A maker's own launcher, found by asking Android who answers Home.
        assertEquals(GuardReason.HOME, CleanupPlan.blocked(pkg("com.tcl.launcher"), tv.copy(home = setOf("com.tcl.launcher"))))
        // Known home screens stay guarded even when another app answers Home right now.
        assertEquals(GuardReason.HOME, CleanupPlan.blocked(pkg("com.google.android.tvlauncher"), tv.copy(home = emptySet())))
    }

    @Test fun wifiSetupIsNeverSwitchedOff() {
        assertEquals(GuardReason.WIFI_SETUP, blocked(CleanupPlan.WIFI_SETUP))
        assertEquals(GuardReason.WIFI_SETUP, CleanupPlan.blocked(pkg(CleanupPlan.WIFI_SETUP), tv.copy(home = emptySet())))
    }

    @Test fun whatHavenDependsOnIsNeverSwitchedOff() {
        assertEquals(GuardReason.HAVEN, blocked(self))
        listOf(
            "com.sony.dtv.configsettings", "com.sony.dtv.settingsdisplay", "com.sony.dtv.tvx", "com.android.providers.tv",
            "com.mediatek.tvinput", "com.maker.tuner", "com.droidlogic.tvinput", "com.maker.hdmi.service", "com.sony.dtv.tvinput.external",
        ).forEach { assertEquals(it, GuardReason.HAVEN, blocked(it)) }
    }

    /** From a BRAVIA's own package list. */
    @Test fun sonysOwnPartsAreNeverSwitchedOff() {
        listOf(
            "com.sony.dtv.tvapi", "com.sony.dtv.webapi.core", "com.sony.dtv.sonysystemservice", "com.sony.dtv.braviasyncservice",
            "com.sony.dtv.braviasyncsetting", "com.sony.dtv.globalkeyoverlay", "com.sony.dtv.irblaster", "com.sony.dtv.systemupdate",
            "com.sony.dtv.broadcastclock.clocksyncservice", "com.sony.dtv.b2b.prosettings", "com.mediatek.agent", "mtktvapi.agent",
            "com.uei.quicksetsdk.sony", "com.dolby.android.audio.service",
            // Not known to Haven, but named like a service or settings: left alone.
            "com.sony.dtv.someotherservice", "com.sony.dtv.newsettings",
        ).forEach { assertEquals(it, GuardReason.TV, blocked(it)) }
    }

    @Test fun sonysJunkAndOptionalFeaturesCanBeSwitchedOff() {
        listOf(
            "com.sony.dtv.promos", "com.sonyericsson.idd.agent", "com.sony.dtv.sonybugreportsys", "com.sony.dtv.multiscreendemo",
            "com.sony.dtv.airplayapp", "com.sony.dtv.voicesearch",
            // Haven knows these, so their names ("service", "remote", "settings") don't hide them.
            "com.sony.dtv.sensingfusionservice", "com.sony.dtv.seeds.iot.remotestart", "com.sony.dtv.sensingcamera.settings",
        ).forEach { assertNull(it, blocked(it)) }
        val scan = Cleanup.scan(listOf(pkg("com.sony.dtv.promos"), pkg("com.sonyericsson.idd.agent"), pkg("com.sony.dtv.voicesearch")))
        assertEquals(3, CleanupPlan.offers(scan, tv).size)
    }

    @Test fun keyboardsAndAccessibilityInUseAreNeverSwitchedOff() {
        assertEquals(GuardReason.INPUT, blocked("com.google.android.leanback.ime"))
        assertEquals(GuardReason.INPUT, blocked("com.maker.reader"))
    }

    @Test fun ordinaryJunkIsAllowed() {
        assertNull(blocked("com.google.android.tvrecommendations"))
        assertNull(blocked("com.sony.dtv.sonyselect"))
        assertNull(blocked("com.netflix.ninja"))
    }

    @Test fun offersOnlyWhatsOnInADisableCategoryAndUnguarded() {
        val scan = Cleanup.scan(
            listOf(
                pkg("com.google.android.tvrecommendations"),
                pkg("com.google.android.feedback", state = PkgState.DISABLED_USER),
                pkg("org.mine", system = false),
                pkg("com.maker.mystery"),
                pkg("com.google.android.apps.tv.launcherx"),
            ),
        )
        assertEquals(listOf("com.google.android.tvrecommendations"), CleanupPlan.offers(scan, tv).map { it.facts.pkg })
        assertEquals(mapOf(GuardReason.HOME to 1), CleanupPlan.guardedCounts(scan, tv))
    }

    @Test fun onlyPlainPackageNamesAreValid() {
        assertTrue(CleanupPlan.validName("com.google.android.tvrecommendations"))
        assertTrue(CleanupPlan.validName("com.maker.app_2"))
        listOf(
            "", "android", "com.x; reboot", "com.x && rm -rf /", "com.x\nreboot", "com.x\$(id)", "com.x`id`", "com..x",
            ".com.x", "com.x.", "1com.x", "com.x y", "com.x/y", "com.x|y", "com.x'", "com.\"x\"", "a.b".repeat(100),
        ).forEach { assertFalse(it, CleanupPlan.validName(it)) }
    }

    @Test fun commandsDisableForTheUserAndNeverUninstall() {
        assertEquals("pm disable-user --user 0 com.netflix.ninja", CleanupPlan.disableCommand("com.netflix.ninja"))
        assertEquals(listOf("pm enable --user 0 com.x.y"), CleanupPlan.restoreCommands("com.x.y", PkgState.ENABLED))
        assertEquals(listOf("pm default-state --user 0 com.x.y", "pm enable --user 0 com.x.y"), CleanupPlan.restoreCommands("com.x.y", PkgState.DEFAULT))
        val all = listOf(CleanupPlan.disableCommand("com.x.y")) + PkgState.entries.flatMap { CleanupPlan.restoreCommands("com.x.y", it) }
        assertTrue(all.none { it.contains("uninstall") || it.contains("clear") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafeNamesNeverBecomeCommands() {
        CleanupPlan.disableCommand("com.x; reboot")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafeNamesNeverBecomeRestoreCommands() {
        CleanupPlan.restoreCommands("com.x && reboot", PkgState.DEFAULT)
    }

    @Test fun theRecordKeepsTheFirstStateAndSkipsWhatWasOffAlready() {
        val first = CleanupPlan.entryFor(pkg("com.x.y", state = PkgState.ENABLED), null, 100, "fp1")!!
        assertEquals(PkgState.ENABLED, first.prior)
        // Disabled again after an update: still the state from before Haven ever touched it.
        assertEquals(first, CleanupPlan.entryFor(pkg("com.x.y", state = PkgState.DEFAULT), first, 200, "fp2"))
        assertNull(CleanupPlan.entryFor(pkg("com.x.y", state = PkgState.DISABLED_USER), null, 100, "fp1"))
    }

    @Test fun recordEntriesSurviveSavingAndReading() {
        val e = CleanupEntry("com.x.y", PkgState.DEFAULT, 1_700_000_000_000, "google/kirkwood/kirkwood:12/STTE.231215.005/1234:user/release-keys")
        assertEquals(e, CleanupEntry.decode("com.x.y", e.encode()))
        assertNull(CleanupEntry.decode("com.x.y", "garbage"))
        assertNull(CleanupEntry.decode("com.x.y", "NOPE|1|fp"))
        assertNull(CleanupEntry.decode("com.x; reboot", e.encode()))
    }

    @Test fun firmwareUpdateCheckOffersOncePerFirmware() {
        val a = CleanupEntry("com.a.a", PkgState.DEFAULT, 1, "old")
        val b = CleanupEntry("com.b.b", PkgState.DEFAULT, 1, "old")
        val back = CleanupPlan.cameBack(listOf(a, b), mapOf("com.a.a" to PkgState.DEFAULT, "com.b.b" to PkgState.DISABLED_USER))
        assertEquals(listOf(a), back)
        assertTrue(CleanupPlan.offerAfterUpdate(back, "new", null))
        assertFalse(CleanupPlan.offerAfterUpdate(back, "new", "new"))
        // Switched back on by hand on the same firmware: shown in Restore, not offered.
        assertFalse(CleanupPlan.offerAfterUpdate(back, "old", null))
        assertFalse(CleanupPlan.offerAfterUpdate(emptyList(), "new", null))
        // A package that's gone isn't "back on".
        assertTrue(CleanupPlan.cameBack(listOf(a), mapOf("com.a.a" to null)).isEmpty())
    }

    @Test fun routeFollowsWhatTheTvOffers() {
        assertEquals(CleanupRoute.READY, CleanupPlan.route(debuggingPortOpen = true, suPresent = false, wirelessDebuggingOn = true))
        assertEquals(CleanupRoute.ROOT, CleanupPlan.route(debuggingPortOpen = false, suPresent = true, wirelessDebuggingOn = true))
        assertEquals(CleanupRoute.WIRELESS_ONLY, CleanupPlan.route(debuggingPortOpen = false, suPresent = false, wirelessDebuggingOn = true))
        assertEquals(CleanupRoute.DEBUGGING_OFF, CleanupPlan.route(debuggingPortOpen = false, suPresent = false, wirelessDebuggingOn = false))
    }
}
