package com.aktvhome.launcher.core.stats;

import java.util.Objects;

/**
 * A made-attempted stat such as field goals ("5-10", or "8.5-17.1" in averages). Kotlin reads
 * the getters as properties ({@code fg.made}) and can unpack it ({@code val (made, attempted) = fg}),
 * since it accepts any Java {@code component1()}/{@code component2()} for that.
 */
public final class MadeAttempted {

    /** Nothing made, nothing attempted: the stand-in when a stat is missing. */
    public static final MadeAttempted NONE = new MadeAttempted(0.0, 0.0);

    private final double made;
    private final double attempted;

    public MadeAttempted(double made, double attempted) {
        this.made = made;
        this.attempted = attempted;
    }

    public double getMade() {
        return made;
    }

    public double getAttempted() {
        return attempted;
    }

    public double component1() {
        return made;
    }

    public double component2() {
        return attempted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MadeAttempted)) return false;
        MadeAttempted m = (MadeAttempted) o;
        return Double.compare(made, m.made) == 0 && Double.compare(attempted, m.attempted) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(made, attempted);
    }

    @Override
    public String toString() {
        return made + "-" + attempted;
    }
}
