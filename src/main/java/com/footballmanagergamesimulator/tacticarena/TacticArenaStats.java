package com.footballmanagergamesimulator.tacticarena;

import java.util.List;

/**
 * fm-arena style averages. Pure functions: league matches of a run are summed into
 * {@link RunTotals}; several runs are averaged per season into {@link Results}.
 */
public final class TacticArenaStats {

    private TacticArenaStats() {
    }

    /** One league match from the tested club's point of view. xG and xPts values may be null. */
    public record MatchLine(boolean home, int goalsFor, int goalsAgainst, Double xgFor, Double xgAgainst,
                            Double xptsFor) {
        public MatchLine(boolean home, int goalsFor, int goalsAgainst, Double xgFor, Double xgAgainst) {
            this(home, goalsFor, goalsAgainst, xgFor, xgAgainst, null);
        }
    }

    /**
     * Expected points of one match: the stored engine value when present, otherwise independent
     * Poisson goals from its xG and xGA (goals 0..10 each side); null when neither is known.
     */
    public static Double expectedPoints(Double storedXpts, Double xgFor, Double xgAgainst) {
        if (storedXpts != null) return storedXpts;
        if (xgFor == null || xgAgainst == null) return null;
        return com.footballmanagergamesimulator.compartment.match.ExpectedPoints
                .poisson(Math.max(0.0, xgFor), Math.max(0.0, xgAgainst)).homeXpts();
    }

    /**
     * {@code xpts}/{@code xptsMatches} were added later: totals stored before them read back as 0,
     * so their splits show {@code xpts: null}.
     */
    public record Totals(int matches, int points, int wins, int draws, int losses,
                         int goalsFor, int goalsAgainst, double xgFor, double xgAgainst, int xgMatches,
                         double xpts, int xptsMatches) {
        static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public Totals(int matches, int points, int wins, int draws, int losses,
                      int goalsFor, int goalsAgainst, double xgFor, double xgAgainst, int xgMatches) {
            this(matches, points, wins, draws, losses, goalsFor, goalsAgainst, xgFor, xgAgainst, xgMatches, 0, 0);
        }

        Totals plus(MatchLine m) {
            int w = m.goalsFor() > m.goalsAgainst() ? 1 : 0;
            int d = m.goalsFor() == m.goalsAgainst() ? 1 : 0;
            int l = m.goalsFor() < m.goalsAgainst() ? 1 : 0;
            boolean xg = m.xgFor() != null && m.xgAgainst() != null;
            boolean xp = m.xptsFor() != null;
            return new Totals(matches + 1, points + 3 * w + d, wins + w, draws + d, losses + l,
                    goalsFor + m.goalsFor(), goalsAgainst + m.goalsAgainst(),
                    xgFor + (xg ? m.xgFor() : 0), xgAgainst + (xg ? m.xgAgainst() : 0), xgMatches + (xg ? 1 : 0),
                    xpts + (xp ? m.xptsFor() : 0), xptsMatches + (xp ? 1 : 0));
        }

        Totals plus(Totals o) {
            return new Totals(matches + o.matches, points + o.points, wins + o.wins, draws + o.draws,
                    losses + o.losses, goalsFor + o.goalsFor, goalsAgainst + o.goalsAgainst,
                    xgFor + o.xgFor, xgAgainst + o.xgAgainst, xgMatches + o.xgMatches,
                    xpts + o.xpts, xptsMatches + o.xptsMatches);
        }
    }

    public record RunTotals(Totals overall, Totals home, Totals away) {}

    /**
     * {@code ptsPct}: points as a share of the maximum, in percent with one decimal (comparable across
     * leagues). {@code xpts}: expected points per season, built like {@code pts} (same matches, same
     * averaging over runs), one decimal; null when no match of the split has a value.
     */
    public record Split(int pld, int pts, int ptsMax, Double ptsPct, int w, int d, int l,
                        int gf, int ga, int gd, Integer xgf, Integer xga, Integer xgd, Double xpts) {
        public Split(int pld, int pts, int ptsMax, Double ptsPct, int w, int d, int l,
                     int gf, int ga, int gd, Integer xgf, Integer xga, Integer xgd) {
            this(pld, pts, ptsMax, ptsPct, w, d, l, gf, ga, gd, xgf, xga, xgd, null);
        }
    }

    public record Results(int basedOn, Split overall, Split home, Split away) {}

    public static RunTotals totals(List<MatchLine> leagueMatches) {
        Totals all = Totals.EMPTY, home = Totals.EMPTY, away = Totals.EMPTY;
        for (MatchLine m : leagueMatches) {
            all = all.plus(m);
            if (m.home()) home = home.plus(m);
            else away = away.plus(m);
        }
        return new RunTotals(all, home, away);
    }

    /** Per-season averages over the given runs; null when there is no run. */
    public static Results average(List<RunTotals> runs) {
        if (runs == null || runs.isEmpty()) return null;
        Totals all = Totals.EMPTY, home = Totals.EMPTY, away = Totals.EMPTY;
        for (RunTotals r : runs) {
            all = all.plus(r.overall());
            home = home.plus(r.home());
            away = away.plus(r.away());
        }
        int n = runs.size();
        return new Results(n, split(all, n), split(home, n), split(away, n));
    }

    static Split split(Totals t, int runs) {
        double n = runs;
        Integer xgf = null, xga = null, xgd = null;
        if (t.xgMatches() > 0) {
            xgf = round(t.xgFor() / n);
            xga = round(t.xgAgainst() / n);
            xgd = round((t.xgFor() - t.xgAgainst()) / n);
        }
        Double ptsPct = t.matches() == 0 ? null : Math.round(t.points() * 1000.0 / (t.matches() * 3)) / 10.0;
        Double xpts = t.xptsMatches() > 0 ? Math.round(t.xpts() / n * 10.0) / 10.0 : null;
        return new Split(t.matches(), round(t.points() / n), round(t.matches() * 3 / n), ptsPct,
                round(t.wins() / n), round(t.draws() / n), round(t.losses() / n),
                round(t.goalsFor() / n), round(t.goalsAgainst() / n),
                round((t.goalsFor() - t.goalsAgainst()) / n), xgf, xga, xgd, xpts);
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }
}
