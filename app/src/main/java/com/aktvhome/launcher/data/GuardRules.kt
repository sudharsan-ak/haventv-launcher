package com.aktvhome.launcher.data

// The Accessibility guard's decisions, in plain Kotlin so they can be unit tested.

/**
 * What the guard remembers: the services you want on, and those it saw on at the last check
 * since this boot ([seen] starts empty after every boot).
 */
data class GuardState(val wanted: Set<String>, val seen: Set<String>)

data class GuardResult(val restore: List<String>, val state: GuardState)

object GuardRules {
    /** How long after boot a missing service counts as dropped by the boot, never as switched off. */
    const val BOOT_WINDOW_MS = 10 * 60_000L

    /**
     * One check. [current] is the enabled list now; [available] tells whether a service's app is
     * installed and loaded; [booting] is true inside the boot window.
     * A wanted service that's missing is restored when its app is loaded, unless you switched it off:
     * that's only when an earlier check this boot saw it on, its app is loaded now, and the boot
     * window is over. A service whose app isn't loaded yet stays wanted.
     */
    fun decide(state: GuardState?, current: Set<String>, available: (String) -> Boolean, booting: Boolean): GuardResult {
        val now = current.map(::normal).toSet()
        // First run: whatever is on now is what you want.
        if (state == null) return GuardResult(emptyList(), GuardState(now, now))
        val wanted = state.wanted.map(::normal).toSet()
        val seen = state.seen.map(::normal).toSet()
        val missing = wanted - now
        val switchedOff = missing.filter { it in seen && available(it) && !booting }.toSet()
        val restore = missing.filter { it !in switchedOff && available(it) }
        // New services you turned on join the wanted list.
        val nextWanted = (wanted - switchedOff) + now
        return GuardResult(restore, GuardState(nextWanted, now + restore))
    }

    /** "pkg/.Svc" and "pkg/pkg.Svc" name the same service; compare them in the long form. */
    fun normal(entry: String): String {
        val slash = entry.indexOf('/')
        if (slash <= 0) return entry
        val pkg = entry.substring(0, slash)
        val cls = entry.substring(slash + 1)
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else entry
    }
}
