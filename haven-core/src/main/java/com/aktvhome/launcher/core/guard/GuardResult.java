package com.aktvhome.launcher.core.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

/** One check's outcome: the services to switch back on, in order, and the state to keep. */
public final class GuardResult {

    private final List<String> restore;
    private final GuardState state;

    public GuardResult(@NotNull List<String> restore, @NotNull GuardState state) {
        this.restore = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(restore, "restore")));
        this.state = Objects.requireNonNull(state, "state");
    }

    @NotNull
    public List<String> getRestore() {
        return restore;
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
        return restore.equals(r.restore) && state.equals(r.state);
    }

    @Override
    public int hashCode() {
        return Objects.hash(restore, state);
    }

    @Override
    public String toString() {
        return "GuardResult(restore=" + restore + ", state=" + state + ")";
    }
}
