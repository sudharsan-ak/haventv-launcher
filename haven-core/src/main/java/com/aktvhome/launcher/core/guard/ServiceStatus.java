package com.aktvhome.launcher.core.guard;

/** One service's state, as the Accessibility tab shows it. */
public enum ServiceStatus {
    /** On, and Haven keeps it on. */
    KEPT_ON(true),
    /** Haven keeps it on, but its app hasn't loaded yet (a USB drive still mounting). */
    WAITING_FOR_APP(true),
    /** Haven keeps it on and is about to switch it back on (it was dropped at boot). */
    TURNING_ON(true),
    /** Haven keeps it on, but it was switched off outside Haven; Haven is asking what you want. */
    SWITCHED_OFF_OUTSIDE(false),
    /** Off; Haven asks again after the next restart. */
    REMIND_AFTER_RESTART(false),
    /** Off, and Haven leaves it alone. */
    OFF(false);

    private final boolean toggled;

    ServiceStatus(boolean toggled) {
        this.toggled = toggled;
    }

    /** Whether the tab's toggle shows on. */
    public boolean isToggled() {
        return toggled;
    }
}
