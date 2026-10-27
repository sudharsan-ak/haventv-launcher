package com.aktvhome.launcher.core.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Accessibility Watchdog's decisions: which of your accessibility services (Haven's Home
 * button service among them) to switch back on after Android drops them, and when to respect you
 * switching one off. Pure logic; AccessibilityGuard in the app reads and writes Android's settings.
 */
public final class GuardRules {

    /** How long after boot a missing service counts as dropped by the boot, never as switched off. */
    public static final long BOOT_WINDOW_MS = 10 * 60_000L;

    private GuardRules() {
    }

    /**
     * One check. {@code current} is the enabled list now; {@code available} tells whether a
     * service's app is installed and loaded; {@code booting} is true inside the boot window.
     * A wanted service that's missing is restored when its app is loaded, unless you switched it
     * off: that's only when an earlier check this boot saw it on, its app is loaded now, and the
     * boot window is over. A service whose app isn't loaded yet stays wanted.
     */
    @NotNull
    public static GuardResult decide(
        @Nullable GuardState state,
        @NotNull Collection<String> current,
        @NotNull Predicate<String> available,
        boolean booting
    ) {
        Set<String> now = normalAll(current);
        // First run: whatever is on now is what you want.
        if (state == null) return new GuardResult(Collections.emptyList(), new GuardState(now, now));
        Set<String> wanted = normalAll(state.getWanted());
        Set<String> seen = normalAll(state.getSeen());

        List<String> restore = new ArrayList<>();
        Set<String> switchedOff = new LinkedHashSet<>();
        for (String service : wanted) {
            if (now.contains(service)) continue;
            boolean loaded = available.test(service);
            if (seen.contains(service) && loaded && !booting) switchedOff.add(service);
            else if (loaded) restore.add(service);
        }
        // New services you turned on join the wanted list.
        Set<String> nextWanted = new LinkedHashSet<>(wanted);
        nextWanted.removeAll(switchedOff);
        nextWanted.addAll(now);
        Set<String> nextSeen = new LinkedHashSet<>(now);
        nextSeen.addAll(restore);
        return new GuardResult(restore, new GuardState(nextWanted, nextSeen));
    }

    /** "pkg/.Svc" and "pkg/pkg.Svc" name the same service; compare them in the long form. */
    @NotNull
    public static String normal(@NotNull String entry) {
        int slash = entry.indexOf('/');
        if (slash <= 0) return entry;
        String pkg = entry.substring(0, slash);
        String cls = entry.substring(slash + 1);
        return cls.startsWith(".") ? pkg + "/" + pkg + cls : entry;
    }

    private static Set<String> normalAll(Collection<String> entries) {
        Set<String> out = new LinkedHashSet<>();
        for (String e : entries) out.add(normal(e));
        return out;
    }
}
