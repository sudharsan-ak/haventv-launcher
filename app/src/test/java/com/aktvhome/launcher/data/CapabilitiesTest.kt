package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CapabilitiesTest {

    /** The Sony as it's set up today: everything granted and paired. */
    private val sony = DeviceFacts(
        isSony = true, sonyPaired = true, homeServiceOn = true, secureSettings = true, tvListings = true,
        notifications = true, files = true, displaySettings = true, hasCec = true, cecOn = true, launcher = "Google TV Home",
    )

    /** A fresh Android TV stick with nothing granted. */
    private val stick = DeviceFacts(
        isSony = false, sonyPaired = false, homeServiceOn = false, secureSettings = false, tvListings = false,
        notifications = false, files = false, displaySettings = true, hasCec = true, cecOn = false, launcher = "Google TV Home",
    )

    private fun Map<Capability, CapStatus>.state(c: Capability) = getValue(c).state

    @Test fun setUpSonyHasEverything() {
        val caps = Capabilities.evaluate(sony)
        Capability.entries.forEach { assertEquals(it.name, CapState.AVAILABLE, caps.state(it)) }
    }

    @Test fun sonyNotPairedNeedsSetupNotHidden() {
        val caps = Capabilities.evaluate(sony.copy(sonyPaired = false))
        assertEquals(CapState.NEEDS_SETUP, caps.state(Capability.TV_CONTROL))
        assertNotNull(caps.getValue(Capability.TV_CONTROL).fix)
        // Sleep still works through the Home button service.
        assertEquals(CapState.AVAILABLE, caps.state(Capability.SLEEP))
    }

    @Test fun stickNeverGetsSonyControls() {
        val caps = Capabilities.evaluate(stick)
        assertEquals(CapState.UNSUPPORTED, caps.state(Capability.TV_CONTROL))
        assertEquals(CapState.UNSUPPORTED, Capabilities.evaluate(stick.copy(secureSettings = true, homeServiceOn = true)).state(Capability.TV_CONTROL))
    }

    @Test fun freshStickNeedsSetupForGrants() {
        val caps = Capabilities.evaluate(stick)
        listOf(Capability.HOME_SERVICE, Capability.SECURE_SETTINGS, Capability.TV_LISTINGS, Capability.NOTIFICATIONS, Capability.FILES, Capability.SLEEP)
            .forEach { assertEquals(it.name, CapState.NEEDS_SETUP, caps.state(it)) }
        assertEquals(CapState.AVAILABLE, caps.state(Capability.VOLUME))
        assertEquals(CapState.AVAILABLE, caps.state(Capability.DISPLAY_SETTINGS))
        assertEquals(CapState.NEEDS_SETUP, caps.state(Capability.CEC))
    }

    @Test fun stickSleepsOnceTheServiceIsOn() {
        assertEquals(CapState.AVAILABLE, Capabilities.evaluate(stick.copy(homeServiceOn = true)).state(Capability.SLEEP))
    }

    @Test fun missingHardwareIsUnsupported() {
        val caps = Capabilities.evaluate(stick.copy(displaySettings = false, hasCec = false))
        assertEquals(CapState.UNSUPPORTED, caps.state(Capability.DISPLAY_SETTINGS))
        assertEquals(CapState.UNSUPPORTED, caps.state(Capability.CEC))
    }
}
