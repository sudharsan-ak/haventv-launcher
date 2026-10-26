package com.aktvhome.launcher.data

// Cleanup's Disable and Restore plan: which packages may never be switched off and why, the
// fixed commands for the ones you choose, the record of each package's state from before, and
// the check after a firmware update. Packages are disabled for the user, never uninstalled.
// Plain Kotlin, unit tested.

/** Why a package is never offered in Disable. */
enum class GuardReason(val label: String) {
    CORE("Core Android"),
    HOME("Runs Home"),
    WIFI_SETUP("Wi-Fi setup: switching it off can delete the saved Wi-Fi network"),
    HAVEN("Haven needs it"),
    INPUT("Keyboard or accessibility service in use"),
}

/** What's in use on this TV right now, found on the device, that Disable must leave alone. */
data class GuardContext(
    /** Haven's own package. */
    val self: String,
    /** Every package that can be Home: the TV's own home screen, Haven, setup and Settings' fallback. */
    val home: Set<String> = emptySet(),
    /** Keyboards that are switched on, and the current one. */
    val keyboards: Set<String> = emptySet(),
    /** Accessibility services that are switched on. */
    val accessibility: Set<String> = emptySet(),
    /** Packages behind the TV's inputs: HDMI ports, tuners, Live TV. */
    val tvInputs: Set<String> = emptySet(),
)

/** How Haven can reach this TV's shell for Disable and Restore, found without asking anything on screen. */
enum class CleanupRoute {
    /** This TV's own debugging is on (ADB on port 5555). */
    READY,

    /** Root looks available (su is there); the root manager may still ask. */
    ROOT,

    /** No root, and nothing listening for debugging. */
    DEBUGGING_OFF,

    /** No root, and only Wireless debugging, which Haven can't use yet. */
    WIRELESS_ONLY,
}

/** One package Haven switched off: its state from before, when, and on which firmware. */
data class CleanupEntry(val pkg: String, val prior: PkgState, val at: Long, val fingerprint: String) {

    /** How it's saved: "STATE|time|fingerprint" (the fingerprint last, as it's the free-form part). */
    fun encode(): String = "${prior.name}|$at|$fingerprint"

    companion object {
        fun decode(pkg: String, saved: String): CleanupEntry? {
            val parts = saved.split('|', limit = 3)
            if (parts.size != 3 || !CleanupPlan.validName(pkg)) return null
            val prior = PkgState.entries.firstOrNull { it.name == parts[0] } ?: return null
            val at = parts[1].toLongOrNull() ?: return null
            return CleanupEntry(pkg, prior, at, parts[2])
        }
    }
}

object CleanupPlan {

    /** What Disable offers. "Installed by you" can be uninstalled as usual; System is never offered. */
    val DISABLE_CATEGORIES = listOf(
        CleanupCategory.ADS, CleanupCategory.TELEMETRY, CleanupCategory.TRACKERS, CleanupCategory.PRELOADED, CleanupCategory.FEATURES,
    )

    /** Google TV's setup, which owns a Wi-Fi network saved during setup. */
    const val WIFI_SETUP = "com.google.android.tungsten.setupwraith"

    /** Home screens Haven knows, guarded even if they aren't answering Home right now. */
    private val KNOWN_HOMES = setOf(
        "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
    )

    /** Packages Haven itself uses: the TV listings provider, Sony's settings and control apps, Live TV. */
    private val HAVEN_NEEDS = setOf(
        "com.android.providers.tv",
        "com.sony.dtv.configsettings", "com.sony.dtv.settingsdisplay", "com.sony.dtv.base.screensettings", "com.sony.dtv.tvx",
        "com.google.android.tv",
    )

    /** Name parts of Sony packages that run the TV's settings, inputs or remote control: all left alone. */
    private val SONY_NEEDS = listOf("settings", "config", "service", "system", "tvinput", "tvplayer", "webapi", "scalar", "ircc", "remote", "hdmi", "cec", "input")

    /** Name parts of the TV's input (HDMI, tuner, CEC) services on any maker's TV. */
    private val INPUT_PARTS = setOf("tvinput", "hdmi", "cec", "tvinputservice")

    private val CORE = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings", "com.android.shell",
        "com.android.phone", "com.android.bluetooth", "com.android.nfc", "com.android.se",
        "com.android.networkstack", "com.google.android.networkstack",
        "com.android.networkstack.tethering", "com.google.android.networkstack.tethering",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.android.webview", "com.google.android.webview", "com.google.android.trichromelibrary",
        "com.android.externalstorage", "com.android.location.fused", "com.android.inputdevices", "com.android.keychain",
        "com.android.server.telecom", "com.android.wifi.resources", "com.google.android.wifi.resources",
        "com.android.ext.services", "com.google.android.ext.services", "com.google.android.modulemetadata",
        "com.android.provision", "com.android.certinstaller", "com.android.dynsystem",
        "com.android.wallpaperbackup", "com.android.sharedstoragebackup",
    )

    /** Null when [f] may be switched off, else why not. */
    fun blocked(f: PackageFacts, ctx: GuardContext): GuardReason? {
        val p = f.pkg
        val parts = p.lowercase().split('.')
        return when {
            p == WIFI_SETUP -> GuardReason.WIFI_SETUP
            p == ctx.self || p in HAVEN_NEEDS || p in ctx.tvInputs || parts.any { it in INPUT_PARTS } ||
                (p.startsWith("com.sony.dtv.") && SONY_NEEDS.any { p.substringAfter("com.sony.dtv.").contains(it) }) -> GuardReason.HAVEN
            p in ctx.home || p in KNOWN_HOMES -> GuardReason.HOME
            p in ctx.keyboards || p in ctx.accessibility -> GuardReason.INPUT
            p in CORE || Cleanup.looksCore(f) || isOverlay(p) -> GuardReason.CORE
            else -> null
        }
    }

    /** Resource overlays change how Android itself looks and behaves; they're part of the system. */
    private fun isOverlay(pkg: String): Boolean = pkg.lowercase().let { it.contains(".overlay") || it.endsWith("overlay") }

    /** What Disable offers from a scan: on, in a Disable category, not guarded and with a plain name. */
    fun offers(scan: Collection<Classified>, ctx: GuardContext): List<Classified> =
        scan.filter { it.category in DISABLE_CATEGORIES && it.facts.state.on && validName(it.facts.pkg) && blocked(it.facts, ctx) == null }

    /** The guarded packages from a scan, counted by reason. */
    fun guardedCounts(scan: Collection<Classified>, ctx: GuardContext): Map<GuardReason, Int> =
        scan.mapNotNull { blocked(it.facts, ctx) }.groupingBy { it }.eachCount()

    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /**
     * A package name as Android allows them (letters, digits and underscores in dot-separated
     * parts), and nothing else: a name that isn't plain never reaches the shell.
     */
    fun validName(pkg: String): Boolean = pkg.length <= 255 && NAME.matches(pkg)

    /** The one command Disable runs for [pkg]: switched off for the user, never uninstalled. */
    fun disableCommand(pkg: String): String {
        require(validName(pkg)) { "Not a package name" }
        return "pm disable-user --user 0 $pkg"
    }

    /**
     * The commands that put [pkg] back to [prior], to try in order until it's on: its default state
     * first (as it came), then plainly enabled (for firmware without default-state).
     */
    fun restoreCommands(pkg: String, prior: PkgState): List<String> {
        require(validName(pkg)) { "Not a package name" }
        return when (prior) {
            PkgState.ENABLED -> listOf("pm enable --user 0 $pkg")
            else -> listOf("pm default-state --user 0 $pkg", "pm enable --user 0 $pkg")
        }
    }

    /**
     * The record entry for switching off [before], saved before the change. An existing entry is
     * kept, so disabling again after an update still remembers the very first state. Null when the
     * package isn't on (it was off already, so Haven has nothing to put back).
     */
    fun entryFor(before: PackageFacts, existing: CleanupEntry?, now: Long, fingerprint: String): CleanupEntry? =
        existing ?: if (before.state.on) CleanupEntry(before.pkg, before.state, now, fingerprint) else null

    /** Recorded packages that are on again; [current] is each one's state now (null: not installed). */
    fun cameBack(record: Collection<CleanupEntry>, current: Map<String, PkgState?>): List<CleanupEntry> =
        record.filter { current[it.pkg]?.on == true }.sortedBy { it.pkg }

    /**
     * Whether to offer, once, to switch off what came back: something is on again since a firmware
     * update (a fingerprint other than the one it was disabled on) and this firmware hasn't been
     * offered yet.
     */
    fun offerAfterUpdate(cameBack: Collection<CleanupEntry>, fingerprint: String, offeredFor: String?): Boolean =
        fingerprint != offeredFor && cameBack.any { it.fingerprint != fingerprint }

    /** How Disable and Restore can reach the shell, from what Haven can see without asking. */
    fun route(debuggingPortOpen: Boolean, suPresent: Boolean, wirelessDebuggingOn: Boolean): CleanupRoute = when {
        debuggingPortOpen -> CleanupRoute.READY
        suPresent -> CleanupRoute.ROOT
        wirelessDebuggingOn -> CleanupRoute.WIRELESS_ONLY
        else -> CleanupRoute.DEBUGGING_OFF
    }
}
