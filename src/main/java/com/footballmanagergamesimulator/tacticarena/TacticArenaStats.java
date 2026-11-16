package com.footballmanagergamesimulator.tacticarena;

import java.util.List;

/**
 * fm-arena style averages. Pure functions: league matches of a run are summed into
 * {@link RunTotals}; several runs are averaged per season into {@link Results}.
 */
public final class TacticArenaStats {

    private TacticArenaStats() {
    }

    /** One league match from the tested club's point of view. xG values may be null. */
    public record MatchLine(boolean home, int goalsFor, int goalsAgainst, Double xgFor, Double xgAgainst) {}

    public record Totals(int matches, int points, int wins, int draws, int losses,
                         int goalsFor, int goalsAgainst, double xgFor, double xgAgainst, int xgMatches) {
        static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        Totals plus(MatchLine m) {
            int w = m.goalsFor() > m.goalsAgainst() ? 1 : 0;
            int d = m.goalsFor() == m.goalsAgainst() ? 1 : 0;
            int l = m.goalsFor() < m.goalsAgainst() ? 1 : 0;
            boolean xg = m.xgFor() != null && m.xgAgainst() != null;
            return new Totals(matches + 1, points + 3 * w + d, wins + w, draws + d, losses + l,
                    goalsFor + m.goalsFor(), goalsAgainst + m.goalsAgainst(),
                    xgFor + (xg ? m.xgFor() : 0), xgAgainst + (xg ? m.xgAgainst() : 0), xgMatches + (xg ? 1 : 0));
        }

        Totals plus(Totals o) {
            return new Totals(matches + o.matches, points + o.points, wins + o.wins, draws + o.draws,
                    losses + o.losses, goalsFor + o.goalsFor, goalsAgainst + o.goalsAgainst,
                    xgFor + o.xgFor, xgAgainst + o.xgAgainst, xgMatches + o.xgMatches);
        }
    }

    public record RunTotals(Totals overall, Totals home, Totals away) {}

    public record Split(int pld, int pts, int ptsMax, int w, int d, int l,
                        int gf, int ga, int gd, Integer xgf, Integer xga, Integer xgd) {}

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
        return new Split(t.matches(), round(t.points() / n), round(t.matches() * 3 / n),
                round(t.wins() / n), round(t.draws() / n), round(t.losses() / n),
                round(t.goalsFor() / n), round(t.goalsAgainst() / n),
                round((t.goalsFor() - t.goalsAgainst()) / n), xgf, xga, xgd);
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }
}
