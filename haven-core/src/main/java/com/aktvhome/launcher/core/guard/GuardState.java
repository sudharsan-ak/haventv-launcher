package com.aktvhome.launcher.core.guard;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

/**
 * What the Accessibility Watchdog remembers: the services you want on, and those it saw on at the
 * last check since this boot ({@code seen} starts empty after every boot). Immutable: the sets are
 * read-only copies.
 */
public final class GuardState {

    private final Set<String> wanted;
    private final Set<String> seen;

    public GuardState(@NotNull Set<String> wanted, @NotNull Set<String> seen) {
        this.wanted = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(wanted, "wanted")));
        this.seen = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(seen, "seen")));
    }

    @NotNull
    public Set<String> getWanted() {
        return wanted;
    }

    @NotNull
    public Set<String> getSeen() {
        return seen;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GuardState)) return false;
        GuardState s = (GuardState) o;
        return wanted.equals(s.wanted) && seen.equals(s.seen);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wanted, seen);
    }

    @Override
    public String toString() {
        return "GuardState(wanted=" + wanted + ", seen=" + seen + ")";
    }
}
