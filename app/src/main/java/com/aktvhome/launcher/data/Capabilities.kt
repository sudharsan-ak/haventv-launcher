package com.aktvhome.launcher.data

// What this TV lets Haven do, worked out from what Haven finds on it rather than from its brand.
// Plain Kotlin, unit tested; DeviceCapabilities gathers the facts on the device.

/** Something Haven can do on some TVs and not others, with how the This TV page names it. */
enum class Capability(val label: String) {
    HOME_SERVICE("Home button service"),
    SECURE_SETTINGS("Private DNS and system switches"),
    TV_CONTROL("Picture, sound and power (Sony)"),
    VOLUME("Volume"),
    SLEEP("Sleep and power"),
    DISPLAY_SETTINGS("Display settings"),
    CEC("HDMI-CEC (display on and off with this device)"),
    TV_LISTINGS("Live channels and continue watching"),
    NOTIFICATIONS("NBA alerts skip the game you're watching"),
    FILES("Backup that survives a reinstall"),
}

enum class CapState {
    /** Works now. */
    AVAILABLE,

    /** The TV can do it, but a grant or a setting is missing. */
    NEEDS_SETUP,

    /** This TV can't do it, whatever is set up. */
    UNSUPPORTED,
}

/** A capability's state, why, and (for Needs setup) what to do about it. */
data class CapStatus(val state: CapState, val reason: String, val fix: String? = null)

/** The facts Haven finds on the device. */
data class DeviceFacts(
    /** A Sony TV: Sony's own settings apps are installed. */
    val isSony: Boolean,
    /** Haven is registered with Sony's control service. */
    val sonyPaired: Boolean,
    val homeServiceOn: Boolean,
    val secureSettings: Boolean,
    val tvListings: Boolean,
    val notifications: Boolean,
    val files: Boolean,
    /** The device has its own display settings page Haven can open. */
    val displaySettings: Boolean,
    val hasCec: Boolean,
    val cecOn: Boolean,
    /** Name of the device's own home screen app, or null if none was found. */
    val launcher: String?,
)

object Capabilities {

    private const val ADB = "Needs the one-time setup (a grant only ADB can give)."

    fun evaluate(f: DeviceFacts): Map<Capability, CapStatus> = Capability.entries.associateWith { c ->
        when (c) {
            Capability.HOME_SERVICE -> when {
                f.homeServiceOn -> CapStatus(CapState.AVAILABLE, f.launcher?.let { "Opens Haven instead of $it." } ?: "On.")
                else -> CapStatus(
                    CapState.NEEDS_SETUP, "Off, so Home, NBA alerts, sleep and pairing can't work.",
                    "Turn on Haven in Settings › Accessibility, or switch it on in TV & network › Home button.",
                )
            }
            Capability.SECURE_SETTINGS ->
                if (f.secureSettings) CapStatus(CapState.AVAILABLE, "Granted.")
                else CapStatus(CapState.NEEDS_SETUP, "Not granted yet.", ADB)
            // Sony's picture, sound and power service only exists on Sony TVs; elsewhere the display
            // isn't Haven's to control (an HDMI stick can't change a projector's picture).
            Capability.TV_CONTROL -> when {
                !f.isSony -> CapStatus(CapState.UNSUPPORTED, "This isn't a Sony TV, so there's no picture or power service to talk to.")
                f.sonyPaired -> CapStatus(CapState.AVAILABLE, "Paired with the TV.")
                else -> CapStatus(
                    CapState.NEEDS_SETUP, "Not paired with the TV yet.",
                    "Turn on Settings › Remote device settings › Control remotely, then press the power button in Quick settings.",
                )
            }
            Capability.VOLUME -> CapStatus(CapState.AVAILABLE, if (f.isSony && f.sonyPaired) "Through the TV." else "This device's own volume.")
            Capability.SLEEP -> when {
                f.isSony && f.sonyPaired -> CapStatus(CapState.AVAILABLE, "Power off and restart through the TV.")
                f.homeServiceOn -> CapStatus(CapState.AVAILABLE, "Sleep through the Home button service; restart from Android's power menu.")
                else -> CapStatus(CapState.NEEDS_SETUP, "Needs the Home button service.", "Turn on the Home button service.")
            }
            Capability.DISPLAY_SETTINGS ->
                if (f.isSony || f.displaySettings) CapStatus(CapState.AVAILABLE, if (f.isSony) "The TV's screen settings." else "This device's display settings.")
                else CapStatus(CapState.UNSUPPORTED, "This device has no display settings page to open.")
            Capability.CEC -> when {
                !f.hasCec -> CapStatus(CapState.UNSUPPORTED, "This device has no HDMI-CEC.")
                f.cecOn -> CapStatus(CapState.AVAILABLE, "On: the display can follow this device on and off.")
                else -> CapStatus(CapState.NEEDS_SETUP, "Off.", "Turn on HDMI-CEC in this device's display or input settings.")
            }
            Capability.TV_LISTINGS ->
                if (f.tvListings) CapStatus(CapState.AVAILABLE, "Granted.") else CapStatus(CapState.NEEDS_SETUP, "Not granted yet.", ADB)
            Capability.NOTIFICATIONS ->
                if (f.notifications) CapStatus(CapState.AVAILABLE, "Granted.") else CapStatus(CapState.NEEDS_SETUP, "Not granted yet.", ADB)
            Capability.FILES ->
                if (f.files) CapStatus(CapState.AVAILABLE, "Granted.") else CapStatus(CapState.NEEDS_SETUP, "Not granted yet.", ADB)
        }
    }
}
