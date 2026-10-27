package com.aktvhome.launcher.core.stats;

import java.util.Locale;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Advanced stats for the player card, worked out from box score numbers. A stat that can't be
 * worked out (its denominator is 0: no shots, no minutes yet) is {@code null}, a boxed
 * {@link Double} that Kotlin reads as {@code Double?}, and is shown as "-".
 */
public final class Advanced {

    private Advanced() {
    }

    /** True shooting %: points per two shooting possessions, PTS / (2 * (FGA + 0.44 * FTA)). */
    @Nullable
    public static Double trueShooting(double pts, double fga, double fta) {
        double shots = 2 * (fga + 0.44 * fta);
        return shots == 0.0 ? null : 100 * pts / shots;
    }

    /** Effective FG %: threes count 1.5 field goals, (FGM + 0.5 * 3PM) / FGA. */
    @Nullable
    public static Double effectiveFg(double fgm, double threes, double fga) {
        return fga == 0.0 ? null : 100 * (fgm + 0.5 * threes) / fga;
    }

    /**
     * Usage %: the share of the team's possessions a player used while on the floor,
     * 100 * ((FGA + 0.44 * FTA + TO) * (teamMIN / 5)) / (MIN * (teamFGA + 0.44 * teamFTA + teamTO)).
     */
    @Nullable
    public static Double usage(double fga, double fta, double to, double min, double teamMin, double teamFga, double teamFta, double teamTo) {
        double bottom = min * (teamFga + 0.44 * teamFta + teamTo);
        return bottom == 0.0 ? null : 100 * ((fga + 0.44 * fta + to) * (teamMin / 5)) / bottom;
    }

    /**
     * John Hollinger's Game Score: PTS + 0.4 FGM - 0.7 FGA - 0.4 (FTA - FTM) + 0.7 OREB + 0.3 DREB
     * + STL + 0.7 AST + 0.7 BLK - 0.4 PF - TO.
     */
    public static double gameScore(
        double pts, double fgm, double fga, double ftm, double fta, double oreb, double dreb,
        double stl, double ast, double blk, double pf, double to
    ) {
        return pts + 0.4 * fgm - 0.7 * fga - 0.4 * (fta - ftm) + 0.7 * oreb + 0.3 * dreb + stl + 0.7 * ast + 0.7 * blk - 0.4 * pf - to;
    }

    /** A ratio such as AST/TO or points per shot; null over 0. */
    @Nullable
    public static Double ratio(double top, double bottom) {
        return bottom == 0.0 ? null : top / bottom;
    }

    /** One decimal, or "-" for a value that can't be worked out. */
    @NotNull
    public static String show(@Nullable Double value) {
        return value == null ? "-" : String.format(Locale.US, "%.1f", value);
    }

    /** "5-10" (or "8.5-17.1" in averages) as made and attempted; null if it isn't that shape. */
    @Nullable
    public static MadeAttempted madeAttempted(@Nullable String text) {
        if (text == null) return null;
        // -1 keeps empty pieces ("5-" is two parts, "--" three), as Kotlin's split does.
        String[] parts = text.split("-", -1);
        if (parts.length != 2) return null;
        Double made = parse(parts[0].trim());
        Double attempted = parse(parts[1].trim());
        if (made == null || attempted == null) return null;
        return new MadeAttempted(made, attempted);
    }

    /** A plain number from a stat cell ("31", "35.2", "+7"); 0 when it's blank or "-". */
    public static double number(@Nullable String text) {
        if (text == null) return 0.0;
        String t = text.trim();
        if (t.startsWith("+")) t = t.substring(1);
        Double value = parse(t);
        return value == null ? 0.0 : value;
    }

    /** The number in {@code text}, or null when it isn't one. */
    @Nullable
    private static Double parse(String text) {
        if (text.isEmpty()) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
