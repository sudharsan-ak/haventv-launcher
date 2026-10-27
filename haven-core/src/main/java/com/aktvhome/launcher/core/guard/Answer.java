package com.aktvhome.launcher.core.guard;

/** Your answer to "this service was switched off" (or to the reminder after a restart). */
public enum Answer {
    /** Switch it back on and keep it on. */
    TURN_ON,
    /** Leave it off for now and ask again after the next restart. No answer counts as this. */
    REMIND,
    /** Leave it off and stop asking. */
    LEAVE_OFF,
}
