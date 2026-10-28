package com.aktvhome.launcher.core.sports;

/**
 * Pure Java decision rules for Haven's optional NBA companion.
 * No networking, UI, Android permissions, or fabricated play-by-play.
 */
public final class NbaMomentEngine {
    private NbaMomentEngine() {}

    /** Stable game score observation; regular class avoids record desugaring on older TVs. */
    public static final class Snapshot {
        private final String id, awayAbbr, homeAbbr, awayName, homeName, state;
        private final int awayScore, homeScore, period;
        private final long observedAt;

        public Snapshot(String id, String awayAbbr, String homeAbbr, String awayName, String homeName,
                        int awayScore, int homeScore, int period, String state, long observedAt) {
            this.id = id;
            this.awayAbbr = awayAbbr;
            this.homeAbbr = homeAbbr;
            this.awayName = awayName;
            this.homeName = homeName;
            this.awayScore = awayScore;
            this.homeScore = homeScore;
            this.period = period;
            this.state = state;
            this.observedAt = observedAt;
        }
        public String id() { return id; }
        public String awayAbbr() { return awayAbbr; }
        public String homeAbbr() { return homeAbbr; }
        public String awayName() { return awayName; }
        public String homeName() { return homeName; }
        public int awayScore() { return awayScore; }
        public int homeScore() { return homeScore; }
        public int period() { return period; }
        public String state() { return state; }
        public long observedAt() { return observedAt; }
        public boolean hasScores() { return awayScore >= 0 && homeScore >= 0; }
        public boolean live() { return "LIVE".equals(state); }
        public boolean finalGame() { return "FINAL".equals(state); }
        public boolean involves(String abbr) {
            return abbr != null && !abbr.isEmpty() && (abbr.equals(awayAbbr) || abbr.equals(homeAbbr));
        }
        public int margin(String abbr) {
            return abbr.equals(awayAbbr) ? awayScore - homeScore : homeScore - awayScore;
        }
        public String name(String abbr) {
            return abbr.equals(awayAbbr) ? awayName : homeName;
        }
        public String other(String abbr) {
            return abbr.equals(awayAbbr) ? homeAbbr : awayAbbr;
        }
        public int points(String abbr) {
            return abbr.equals(awayAbbr) ? awayScore : homeScore;
        }
    }

    public static final class Moment {
        private final String key, headline, detail, safeHeadline, safeDetail;
        private final boolean liveOnly, urgent;
        private final int seconds;

        public Moment(String key, String headline, String detail, String safeHeadline, String safeDetail,
                      boolean liveOnly, boolean urgent, int seconds) {
            this.key = key;
            this.headline = headline;
            this.detail = detail;
            this.safeHeadline = safeHeadline;
            this.safeDetail = safeDetail;
            this.liveOnly = liveOnly;
            this.urgent = urgent;
            this.seconds = seconds;
        }
        public String key() { return key; }
        public String headline() { return headline; }
        public String detail() { return detail; }
        public String headline(boolean hideScores) { return hideScores ? safeHeadline : headline; }
        public String detail(boolean hideScores) { return hideScores ? safeDetail : detail; }
        public boolean liveOnly() { return liveOnly; }
        public boolean urgent() { return urgent; }
        public int seconds() { return seconds; }
    }

    private static boolean compatible(Snapshot a, Snapshot b) {
        return a != null && b != null && a.id().equals(b.id()) &&
            a.awayAbbr().equals(b.awayAbbr()) && a.homeAbbr().equals(b.homeAbbr());
    }
    private static String subject(Snapshot g, String favorite) {
        if (g.involves(favorite)) return favorite;
        return g.awayScore() >= g.homeScore() ? g.awayAbbr() : g.homeAbbr();
    }
    private static String comparison(Snapshot old, Snapshot now, String team) {
        int scored = now.points(team) - old.points(team);
        int allowed = now.points(now.other(team)) - old.points(old.other(team));
        return now.name(team) + " have scored " + scored + " to " + allowed +
            " since your previous visit. Previously " + old.awayScore() + "–" + old.homeScore() +
            "; now " + now.awayScore() + "–" + now.homeScore() + ".";
    }

    /** Full details are available whenever you explicitly reopen the same game. */
    public static Moment sinceChecked(Snapshot old, Snapshot now, String favorite) {
        if (!compatible(old, now) || !now.hasScores() || !old.hasScores()) return null;
        if (now.observedAt() <= old.observedAt() + 30_000L) return null;
        // A corrected feed may briefly revise a score downward; do not invent a run.
        if (now.awayScore() < old.awayScore() || now.homeScore() < old.homeScore()) return null;
        String team = subject(now, favorite);
        String teamName = now.name(team);
        String safe = "There's an update in the " + now.awayName() + " vs " + now.homeName() + " game.";
        if (old.state().equals("PRE") && now.live()) {
            return new Moment(now.id() + ":started", teamName + " are underway.",
                "The game has started since you last checked.", safe,
                "The game has started since your last visit.", true, true, 15);
        }
        if (now.finalGame() && old.finalGame()) return null;
        if (now.finalGame()) {
            return new Moment(now.id() + ":final", now.awayName() + " vs " + now.homeName() + " has finished.",
                "The game finished since you last checked. Final score: " + now.awayScore() + "–" + now.homeScore() + ".",
                safe, "This game has finished since your last visit.", false, true, 15);
        }
        if (!now.live() || !old.live()) return null;
        int prev = old.margin(team);
        int current = now.margin(team);
        String detail = comparison(old, now, team);
        if (old.period() < 5 && now.period() >= 5) {
            return new Moment(now.id() + ":overtime", now.awayName() + " vs " + now.homeName() + " went to overtime.",
                detail + " The game has entered overtime.", safe, "The game has reached overtime.", true, true, 15);
        }
        if (prev * current < 0 && Math.abs(current) >= 3) {
            String who = current > 0 ? teamName : now.name(now.other(team));
            return new Moment(now.id() + ":reversal:" + who,
                who + " have taken the lead since you last checked.", detail,
                safe, "The lead has changed hands since your last visit.", true, true, 15);
        }
        if (Math.abs(current - prev) >= 12) {
            return new Moment(now.id() + ":swing:" + (current / 8),
                "There's been a big momentum swing in " + teamName + "'s game.",
                detail, safe, "There has been a significant scoring change.", true, false, 15);
        }
        if (now.involves(favorite) && prev > 0 && current - prev >= 6) {
            return new Moment(now.id() + ":extended:" + (current / 6),
                teamName + " have extended their lead to " + current + ".",
                detail, safe, "Your team's game has a new update.", true, false, 15);
        }
        return new Moment(now.id() + ":ordinary:" + now.observedAt(),
            null, detail, null, "The score has changed since your last visit.", false, false, 15);
    }

    /** Live favorite-team moments can appear without a user checking that game first. */
    public static Moment favoriteLive(Snapshot anchor, Snapshot now, String favorite) {
        if (!compatible(anchor, now) || !anchor.live() || !now.live() ||
            !anchor.hasScores() || !now.hasScores() || !now.involves(favorite) ||
            now.awayScore() < anchor.awayScore() || now.homeScore() < anchor.homeScore()) return null;
        String name = now.name(favorite);
        int oldMargin = anchor.margin(favorite);
        int margin = now.margin(favorite);
        String safe = "There's an update in " + name + "'s live game.";
        if (anchor.period() < 5 && now.period() >= 5) {
            return new Moment(now.id() + ":fav-ot:" + now.period(), name + " are heading into overtime!",
                "The game went into overtime.", safe, "The game is in overtime.", true, true, 12);
        }
        if (oldMargin < 0 && margin >= 0) {
            return new Moment(now.id() + ":fav-comeback", name + " have erased the deficit!",
                "The team has come back from " + (-oldMargin) + " down.", safe,
                "Your team has fought back.", true, true, 12);
        }
        if (oldMargin > 0 && margin < 0) {
            return new Moment(now.id() + ":fav-lost-lead", name + " have surrendered their lead.",
                "The other team has taken the lead.", safe, "The lead has changed.", true, true, 12);
        }
        if (oldMargin >= 0 && margin >= 6 && margin - oldMargin >= 6) {
            return new Moment(now.id() + ":fav-extend:" + (margin / 6),
                name + " have stretched their lead to " + margin + "!",
                "The lead has grown by " + (margin - oldMargin) + " points.", safe,
                "Your team has a new live-game update.", true, false, 12);
        }
        return null;
    }
}
