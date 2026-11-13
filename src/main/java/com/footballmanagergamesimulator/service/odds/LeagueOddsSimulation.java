package com.footballmanagergamesimulator.service.odds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The rest of a league season played many times over (design DESIGN_MEDIA_SI_COMPETITII §4.1). Starts from the real
 * table; every remaining fixture is only a win, a draw or a defeat, drawn from the engine's own probabilities. Level
 * on points, the current goal difference decides, then chance. Pure: no Spring, no database.
 */
public final class LeagueOddsSimulation {

    /** A fixture still to play, as indexes into the table, with the home side's win and draw probabilities. */
    public record Fixture(int home, int away, double homeWin, double draw) {}

    /**
     * One team's chances, as fractions 0..1. {@code champions} is the chance of a League of Champions place (0 when
     * the league has none); {@code relegation} 0 when nobody goes down.
     */
    public record Odds(long teamId, double title, double top3, double champions, double relegation,
                       double averagePosition) {}

    private LeagueOddsSimulation() {}

    /**
     * @param championsSpots  League of Champions places (positions 1..n), 0 for none
     * @param relegationFrom  first relegated position (1-based), or null when nobody goes down
     */
    public static List<Odds> simulate(long[] teamIds, int[] points, int[] goalDifference, List<Fixture> fixtures,
                                      int championsSpots, Integer relegationFrom, int runs, long seed) {
        int n = teamIds.length;
        if (points.length != n || goalDifference.length != n) {
            throw new IllegalArgumentException("teamIds, points and goalDifference must have the same length");
        }
        if (n == 0 || runs <= 0) return List.of();
        Random random = new Random(seed);
        int[] titles = new int[n];
        int[] top3 = new int[n];
        int[] champions = new int[n];
        int[] relegated = new int[n];
        long[] positionSum = new long[n];
        int[] simulated = new int[n];
        double[] tieBreak = new double[n];
        Integer[] order = new Integer[n];

        for (int run = 0; run < runs; run++) {
            System.arraycopy(points, 0, simulated, 0, n);
            for (Fixture fixture : fixtures) {
                double draw = random.nextDouble();
                if (draw < fixture.homeWin()) {
                    simulated[fixture.home()] += 3;
                } else if (draw < fixture.homeWin() + fixture.draw()) {
                    simulated[fixture.home()] += 1;
                    simulated[fixture.away()] += 1;
                } else {
                    simulated[fixture.away()] += 3;
                }
            }
            for (int i = 0; i < n; i++) {
                order[i] = i;
                tieBreak[i] = random.nextDouble();
            }
            Arrays.sort(order, (a, b) -> {
                if (simulated[a] != simulated[b]) return Integer.compare(simulated[b], simulated[a]);
                if (goalDifference[a] != goalDifference[b]) return Integer.compare(goalDifference[b], goalDifference[a]);
                return Double.compare(tieBreak[a], tieBreak[b]);
            });
            for (int place = 1; place <= n; place++) {
                int team = order[place - 1];
                positionSum[team] += place;
                if (place == 1) titles[team]++;
                if (place <= 3) top3[team]++;
                if (place <= championsSpots) champions[team]++;
                if (relegationFrom != null && place >= relegationFrom) relegated[team]++;
            }
        }

        List<Odds> odds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            odds.add(new Odds(teamIds[i], (double) titles[i] / runs, (double) top3[i] / runs,
                    (double) champions[i] / runs, (double) relegated[i] / runs, (double) positionSum[i] / runs));
        }
        return odds;
    }

    /** The decimal odds equivalent to a chance, with no bookmaker margin; null when the chance is 0. */
    public static Double decimalOdds(double chance) {
        if (chance <= 0) return null;
        return Math.round(100.0 / chance) / 100.0;
    }
}
