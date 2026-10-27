package com.aktvhome.launcher.core.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

/**
 * One check's outcome: services to switch back on now without asking, services to ask about
 * (switched off outside Haven), and the state to keep.
 */
public final class GuardResult {

    private final List<String> restore;
    private final List<String> ask;
    private final GuardState state;

    public GuardResult(@NotNull List<String> restore, @NotNull List<String> ask, @NotNull GuardState state) {
        this.restore = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(restore, "restore")));
        this.ask = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(ask, "ask")));
        this.state = Objects.requireNonNull(state, "state");
    }

    @NotNull
    public List<String> getRestore() {
        return restore;
    }

    @NotNull
    public List<String> getAsk() {
        return ask;
    }

    @NotNull
    public GuardState getState() {
        return state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GuardResult)) return false;
        GuardResult r = (GuardResult) o;
        return restore.equals(r.restore) && ask.equals(r.ask) && state.equals(r.state);
    }

    @Override
    public int hashCode() {
        return Objects.hash(restore, ask, state);
    }

    @Override
    public String toString() {
        return "GuardResult(restore=" + restore + ", ask=" + ask + ", state=" + state + ")";
    }
}
