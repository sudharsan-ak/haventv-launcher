package com.aktvhome.launcher.core.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Accessibility Watchdog's decisions. Pure logic; AccessibilityGuard in the app reads and
 * writes Android's settings and draws the questions.
 *
 * <p>Each service has a toggle in Haven's Accessibility tab ({@link Keep}). The rules:
 * <ul>
 *   <li>Toggle on: Haven keeps it on. When the TV drops it at boot, or while its app wasn't loaded
 *       (a USB drive), Haven switches it back on once the app loads, without asking.</li>
 *   <li>Switched off outside Haven (the TV's own settings) while Haven is watching: Haven asks
 *       ({@link GuardResult#getAsk()}) instead of guessing. Only an answer, or the tab's toggle,
 *       makes a lasting decision; no answer means "ask again after the next restart".</li>
 *   <li>Switched on anywhere: its toggle turns on, and Haven keeps it on from then.</li>
 *   <li>Haven's own Home button service is left out: it has its own tab and keeper.</li>
 * </ul>
 */
public final class GuardRules {

    /** How long after boot a missing service counts as dropped by the boot, at most. */
    public static final long BOOT_WINDOW_MS = 10 * 60_000L;

    private GuardRules() {
    }

    /**
     * The state on first run. Services Haven already kept on ({@code legacyWanted}, from versions
     * before the Accessibility tab) start toggled on, so nobody loses protection when they update;
     * with no earlier list, whatever is on now does.
     */
    @NotNull
    public static GuardState start(@Nullable Collection<String> legacyWanted, @NotNull Collection<String> current, @NotNull String selfPackage) {
        Map<String, Keep> keep = new LinkedHashMap<>();
        for (String s : legacyWanted != null ? legacyWanted : current) {
            String n = normal(s);
            if (!isSelf(n, selfPackage)) keep.put(n, Keep.ON);
        }
        return new GuardState(keep, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    /**
     * One check of the enabled list {@code current}. {@code loaded} tells whether a service's app
     * is installed and loaded; {@code booting} is true until the TV has settled after a restart
     * (see AccessibilityGuard). A toggled-on service that's missing is switched back on silently
     * when it was dropped (at boot, or while its app wasn't loaded) and asked about otherwise.
     */
    @NotNull
    public static GuardResult decide(
        @NotNull GuardState state,
        @NotNull Collection<String> current,
        @NotNull Predicate<String> loaded,
        boolean booting,
        @NotNull String selfPackage
    ) {
        Set<String> now = normalAll(current);
        Map<String, Keep> keep = new LinkedHashMap<>(state.getKeep());
        Set<String> dropped = new LinkedHashSet<>(state.getDropped());
        Set<String> asking = new LinkedHashSet<>(state.getAsking());

        // Switched on anywhere: Haven keeps it on from now on.
        for (String s : now) {
            if (isSelf(s, selfPackage)) continue;
            keep.put(s, Keep.ON);
            dropped.remove(s);
            asking.remove(s);
        }

        List<String> restore = new ArrayList<>();
        List<String> ask = new ArrayList<>();
        for (Map.Entry<String, Keep> e : keep.entrySet()) {
            String s = e.getKey();
            if (e.getValue() != Keep.ON || now.contains(s) || isSelf(s, selfPackage) || asking.contains(s)) continue;
            if (!loaded.test(s)) {
                // Its app isn't there (a USB drive still mounting, or gone): Android drops the
                // service with it. That isn't you; switch it back on once the app is back.
                dropped.add(s);
            } else if (booting || dropped.contains(s)) {
                restore.add(s);
                dropped.remove(s);
            } else {
                ask.add(s);
                asking.add(s);
            }
        }
        return new GuardResult(restore, ask, new GuardState(keep, dropped, asking, state.getReminded()));
    }

    /**
     * A new boot. Nothing has been reminded about yet, nothing is being asked, and anything still
     * unanswered becomes a reminder (no answer means "ask again after the next restart").
     */
    @NotNull
    public static GuardState newBoot(@NotNull GuardState state) {
        Map<String, Keep> keep = new LinkedHashMap<>(state.getKeep());
        for (String s : state.getAsking()) keep.put(s, Keep.REMIND);
        return new GuardState(keep, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    /** The tab's toggle: on keeps the service on from now, off leaves it alone. */
    @NotNull
    public static GuardState setKeep(@NotNull GuardState state, @NotNull String service, boolean on) {
        String s = normal(service);
        Map<String, Keep> keep = new LinkedHashMap<>(state.getKeep());
        keep.put(s, on ? Keep.ON : Keep.OFF);
        return new GuardState(keep, without(state.getDropped(), s), without(state.getAsking(), s), state.getReminded());
    }

    /** Your answer to the question or the reminder about {@code service}. */
    @NotNull
    public static GuardState answer(@NotNull GuardState state, @NotNull String service, @NotNull Answer answer) {
        String s = normal(service);
        Map<String, Keep> keep = new LinkedHashMap<>(state.getKeep());
        switch (answer) {
            case TURN_ON:
                keep.put(s, Keep.ON);
                break;
            case REMIND:
                keep.put(s, Keep.REMIND);
                break;
            case LEAVE_OFF:
                keep.put(s, Keep.OFF);
                break;
        }
        return new GuardState(keep, without(state.getDropped(), s), without(state.getAsking(), s), state.getReminded());
    }

    /**
     * Services to remind you about now: waiting for "after the next restart", still off, their
     * app loaded, and not yet reminded about since this boot.
     */
    @NotNull
    public static List<String> reminders(
        @NotNull GuardState state,
        @NotNull Collection<String> current,
        @NotNull Predicate<String> loaded,
        @NotNull String selfPackage
    ) {
        Set<String> now = normalAll(current);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Keep> e : state.getKeep().entrySet()) {
            String s = e.getKey();
            if (e.getValue() == Keep.REMIND && !now.contains(s) && !isSelf(s, selfPackage)
                && !state.getReminded().contains(s) && loaded.test(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** {@code service} was reminded about (shown for its full time) since this boot. */
    @NotNull
    public static GuardState reminded(@NotNull GuardState state, @NotNull String service) {
        Set<String> reminded = new LinkedHashSet<>(state.getReminded());
        reminded.add(normal(service));
        return new GuardState(state.getKeep(), state.getDropped(), state.getAsking(), reminded);
    }

    /**
     * Whether the TV has settled after a restart: every toggled-on service's app is loaded and
     * the service is on. Until then, missing services count as dropped by the boot.
     */
    public static boolean settled(
        @NotNull GuardState state,
        @NotNull Collection<String> current,
        @NotNull Predicate<String> loaded,
        @NotNull String selfPackage
    ) {
        Set<String> now = normalAll(current);
        for (Map.Entry<String, Keep> e : state.getKeep().entrySet()) {
            String s = e.getKey();
            if (e.getValue() != Keep.ON || isSelf(s, selfPackage)) continue;
            if (!now.contains(s) || !loaded.test(s)) return false;
        }
        return true;
    }

    /** How the tab shows {@code service}. */
    @NotNull
    public static ServiceStatus status(@NotNull GuardState state, @NotNull String service, @NotNull Collection<String> current, boolean loaded) {
        String s = normal(service);
        if (normalAll(current).contains(s)) return ServiceStatus.KEPT_ON;
        Keep k = state.getKeep().get(s);
        if (k == Keep.REMIND) return ServiceStatus.REMIND_AFTER_RESTART;
        if (k != Keep.ON) return ServiceStatus.OFF;
        if (state.getAsking().contains(s)) return ServiceStatus.SWITCHED_OFF_OUTSIDE;
        return loaded ? ServiceStatus.TURNING_ON : ServiceStatus.WAITING_FOR_APP;
    }

    /** Whether {@code service} belongs to Haven itself (its Home button service, kept by its own tab). */
    public static boolean isSelf(@NotNull String service, @NotNull String selfPackage) {
        int slash = service.indexOf('/');
        return slash > 0 && service.substring(0, slash).equals(selfPackage);
    }

    /** "pkg/.Svc" and "pkg/pkg.Svc" name the same service; compare them in the long form. */
    @NotNull
    public static String normal(@NotNull String entry) {
        String e = entry.trim();
        int slash = e.indexOf('/');
        if (slash <= 0) return e;
        String pkg = e.substring(0, slash);
        String cls = e.substring(slash + 1);
        return cls.startsWith(".") ? pkg + "/" + pkg + cls : e;
    }

    private static Set<String> normalAll(Collection<String> entries) {
        Set<String> out = new LinkedHashSet<>();
        for (String e : entries) if (!e.trim().isEmpty()) out.add(normal(e));
        return out;
    }

    private static Set<String> without(Set<String> set, String item) {
        Set<String> out = new LinkedHashSet<>(set);
        out.remove(item);
        return out;
    }
}
