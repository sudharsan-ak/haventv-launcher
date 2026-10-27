package com.aktvhome.launcher.core.guard;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the Accessibility Watchdog remembers, by service (in the long "pkg/pkg.Service" form):
 * <ul>
 *   <li>{@code keep}: the tab's toggle for each service (see {@link Keep});</li>
 *   <li>{@code dropped}: services that went off while their app wasn't loaded (a USB drive), to
 *       switch back on without asking once it loads;</li>
 *   <li>{@code asking}: services switched off outside Haven that Haven is asking about;</li>
 *   <li>{@code reminded}: services already reminded about since this boot.</li>
 * </ul>
 * Immutable: the collections are read-only copies. Saved as plain strings ({@link #encodeKeep()},
 * {@link #encodeSet(Collection)}) because Android's settings separate services with ':'.
 */
public final class GuardState {

    public static final GuardState EMPTY = new GuardState(
        Collections.emptyMap(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

    private final Map<String, Keep> keep;
    private final Set<String> dropped;
    private final Set<String> asking;
    private final Set<String> reminded;

    public GuardState(
        @NotNull Map<String, Keep> keep,
        @NotNull Collection<String> dropped,
        @NotNull Collection<String> asking,
        @NotNull Collection<String> reminded
    ) {
        Map<String, Keep> k = new LinkedHashMap<>();
        for (Map.Entry<String, Keep> e : keep.entrySet()) k.put(GuardRules.normal(e.getKey()), Objects.requireNonNull(e.getValue()));
        this.keep = Collections.unmodifiableMap(k);
        this.dropped = normalSet(dropped);
        this.asking = normalSet(asking);
        this.reminded = normalSet(reminded);
    }

    @NotNull
    public Map<String, Keep> getKeep() {
        return keep;
    }

    @NotNull
    public Set<String> getDropped() {
        return dropped;
    }

    @NotNull
    public Set<String> getAsking() {
        return asking;
    }

    @NotNull
    public Set<String> getReminded() {
        return reminded;
    }

    /** The toggle for {@code service}, or null when Haven has never seen it on. */
    @Nullable
    public Keep keepOf(@NotNull String service) {
        return keep.get(GuardRules.normal(service));
    }

    /** "pkg/pkg.A=ON:pkg/pkg.B=REMIND". */
    @NotNull
    public String encodeKeep() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Keep> e : keep.entrySet()) {
            if (sb.length() > 0) sb.append(':');
            sb.append(e.getKey()).append('=').append(e.getValue().name());
        }
        return sb.toString();
    }

    /** Reads {@link #encodeKeep()} back, skipping anything that isn't "service=STATE". */
    @NotNull
    public static Map<String, Keep> decodeKeep(@Nullable String saved) {
        Map<String, Keep> out = new LinkedHashMap<>();
        if (saved == null) return out;
        for (String part : saved.split(":")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(part.substring(0, eq), Keep.valueOf(part.substring(eq + 1)));
            } catch (IllegalArgumentException ignored) {
                // An unknown state from a newer version: leave that service out.
            }
        }
        return out;
    }

    @NotNull
    public static String encodeSet(@NotNull Collection<String> services) {
        return String.join(":", services);
    }

    @NotNull
    public static Set<String> decodeSet(@Nullable String saved) {
        Set<String> out = new LinkedHashSet<>();
        if (saved == null) return out;
        for (String part : saved.split(":")) if (!part.trim().isEmpty()) out.add(part.trim());
        return out;
    }

    private static Set<String> normalSet(Collection<String> services) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : services) out.add(GuardRules.normal(s));
        return Collections.unmodifiableSet(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GuardState)) return false;
        GuardState s = (GuardState) o;
        return keep.equals(s.keep) && dropped.equals(s.dropped) && asking.equals(s.asking) && reminded.equals(s.reminded);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keep, dropped, asking, reminded);
    }

    @Override
    public String toString() {
        return "GuardState(keep=" + keep + ", dropped=" + dropped + ", asking=" + asking + ", reminded=" + reminded + ")";
    }
}
