package com.aktvhome.launcher.core.corner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Takes turns for the top-right corner, where NBA alerts, the Last night recap and accessibility
 * questions all appear. One toast at a time; the next waits for it to close, and among those
 * waiting the most urgent ({@link CornerKind} order) goes first.
 *
 * <p>A toast that's ready says so ({@link #want}), then asks for the corner ({@link #tryStart})
 * and gives it back when it closes ({@link #finish}). It gets the corner only when nothing is
 * showing and nothing more urgent is waiting. Safe from any thread.
 */
public final class CornerQueue {

    private final int[] waiting = new int[CornerKind.values().length];
    @Nullable
    private CornerKind active;

    /** A toast of this kind is ready and waiting for the corner. */
    public synchronized void want(@NotNull CornerKind kind) {
        waiting[kind.ordinal()]++;
    }

    /** A waiting toast of this kind no longer needs the corner (dropped before it showed). */
    public synchronized void withdraw(@NotNull CornerKind kind) {
        if (waiting[kind.ordinal()] > 0) waiting[kind.ordinal()]--;
    }

    /**
     * Takes the corner for a toast of this kind: true when nothing is showing and nothing more
     * urgent is waiting. A toast that said it was waiting stops waiting once it has the corner.
     */
    public synchronized boolean tryStart(@NotNull CornerKind kind) {
        if (active != null) return false;
        for (int i = 0; i < kind.ordinal(); i++) if (waiting[i] > 0) return false;
        active = kind;
        if (waiting[kind.ordinal()] > 0) waiting[kind.ordinal()]--;
        return true;
    }

    /** The toast of this kind closed. */
    public synchronized void finish(@NotNull CornerKind kind) {
        if (active == kind) active = null;
    }

    /** What's showing, if anything. */
    @Nullable
    public synchronized CornerKind getActive() {
        return active;
    }

    /** How many toasts of this kind are waiting. */
    public synchronized int waiting(@NotNull CornerKind kind) {
        return waiting[kind.ordinal()];
    }
}
