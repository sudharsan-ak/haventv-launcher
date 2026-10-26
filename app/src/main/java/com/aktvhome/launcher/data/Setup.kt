package com.aktvhome.launcher.data

// The one-time setup's plan: which capabilities it can fix and the fixed shell commands it runs
// for them. The setup never runs anything but these; Cleanup has its own fixed commands, for the
// packages you choose (CleanupPlan). Plain Kotlin, unit tested.

object Setup {

    /** What the setup can fix itself; the rest (HDMI-CEC, Sony pairing) are the TV's own settings. */
    val FIXABLE = listOf(
        Capability.SECURE_SETTINGS,
        Capability.HOME_SERVICE,
        Capability.TV_LISTINGS,
        Capability.NOTIFICATIONS,
        Capability.FILES,
    )

    /** The fixable capabilities that still need setting up, in [FIXABLE] order. */
    fun needed(status: Map<Capability, CapStatus>): List<Capability> =
        FIXABLE.filter { status[it]?.state == CapState.NEEDS_SETUP }

    /**
     * The shell commands for [needed], in order. The Home button service isn't a command: once
     * Private DNS and system switches is granted, Haven turns it on itself. On Android 13 and
     * later, "restricted settings" can stop it being turned on by hand, so that's lifted too.
     */
    fun commands(pkg: String, needed: Collection<Capability>, sdk: Int): List<String> = buildList {
        if (Capability.SECURE_SETTINGS in needed || Capability.HOME_SERVICE in needed) {
            add("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        }
        if (Capability.HOME_SERVICE in needed && sdk >= 33) add("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        if (Capability.TV_LISTINGS in needed) add("pm grant $pkg android.permission.READ_TV_LISTINGS")
        if (Capability.NOTIFICATIONS in needed) add("cmd notification allow_listener $pkg/$pkg.HavenMediaListener 0")
        if (Capability.FILES in needed) add("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
    }

    /** Every command, as typed on a computer, for doing the setup without Haven's help. */
    fun computerCommands(pkg: String, sdk: Int): List<String> = commands(pkg, FIXABLE, sdk).map { "adb shell $it" }
}
