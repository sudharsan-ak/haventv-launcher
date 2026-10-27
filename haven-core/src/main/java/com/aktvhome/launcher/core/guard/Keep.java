package com.aktvhome.launcher.core.guard;

/** What Haven does with one accessibility service: the Accessibility tab's toggle, plus a pending reminder. */
public enum Keep {
    /** Toggle on: Haven turns it on and keeps it on, through restarts. */
    ON,
    /** Toggle off: Haven leaves it alone. */
    OFF,
    /** Toggle off for now: switched off outside Haven without a final answer, so Haven asks again after the next restart. */
    REMIND,
}
