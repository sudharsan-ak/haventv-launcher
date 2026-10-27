package com.aktvhome.launcher.core.corner;

/** The toasts that share the top-right corner, most urgent first. */
public enum CornerKind {
    /** "This service was switched off": a reply to something you just did. */
    SWITCHED_OFF,
    /** An NBA alert: a close game is only close for a few minutes. */
    NBA_ALERT,
    /** The morning Last night recap. */
    RECAP,
    /** An accessibility reminder after a restart: the service has been off since before it. */
    REMINDER,
}
