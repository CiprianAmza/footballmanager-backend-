package com.footballmanagergamesimulator.algorithms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Places the rounds of an extra competition on calendar days that are free for
 * every participant, spread as evenly as possible over a window.
 *
 * <p>Round {@code k} aims at the {@code k}-th of {@code rounds} evenly spaced target
 * days of the window. Among all ways to put the rounds, in order and at least
 * {@link #MIN_GAP} days apart, on free days, the planner picks the one with the
 * lowest total cost: the distance of each round from its target plus a heavy
 * penalty for every neighbouring day on which a participant already plays (which
 * would leave that club no rest day). Solved exactly by dynamic programming over
 * the free days.
 */
public final class DiamondLeagueDayPlanner {

    /** Minimum distance between two rounds of the competition itself. */
    public static final int MIN_GAP = 4;
    /** Cost of a busy neighbouring day, in days of distance from the target. */
    private static final long NO_REST_PENALTY = 25;
    private static final long INF = Long.MAX_VALUE / 4;

    private DiamondLeagueDayPlanner() {}

    /**
     * @param rounds   number of rounds to place
     * @param busyDays days on which at least one participant already plays
     * @param firstDay first day of the window (inclusive)
     * @param lastDay  last day of the window (inclusive)
     * @return one day per round, ascending; {@code null} when the rounds do not fit
     */
    public static int[] plan(int rounds, Set<Integer> busyDays, int firstDay, int lastDay) {
        if (rounds <= 0) return new int[0];
        List<Integer> free = new ArrayList<>();
        for (int day = firstDay; day <= lastDay; day++) {
            if (!busyDays.contains(day)) free.add(day);
        }
        int n = free.size();
        if (n < rounds) return null;

        double interval = rounds == 1 ? 0 : (double) (lastDay - firstDay) / (rounds - 1);
        long[] previous = new long[n];
        long[] current = new long[n];
        int[][] from = new int[rounds][n];
        for (int k = 0; k < rounds; k++) {
            int target = firstDay + (int) Math.round(k * interval);
            Arrays.fill(current, INF);
            // best[k-1] over free days at least MIN_GAP before free[j], as a running minimum
            long bestBefore = INF;
            int bestIndex = -1;
            int pointer = 0;
            for (int j = 0; j < n; j++) {
                int day = free.get(j);
                if (k > 0) {
                    while (pointer < n && free.get(pointer) <= day - MIN_GAP) {
                        if (previous[pointer] < bestBefore) {
                            bestBefore = previous[pointer];
                            bestIndex = pointer;
                        }
                        pointer++;
                    }
                    if (bestBefore >= INF) continue;
                }
                long cost = Math.abs(day - target);
                if (busyDays.contains(day - 1)) cost += NO_REST_PENALTY;
                if (busyDays.contains(day + 1)) cost += NO_REST_PENALTY;
                current[j] = (k == 0 ? 0 : bestBefore) + cost;
                from[k][j] = bestIndex;
            }
            long[] swap = previous;
            previous = current;
            current = swap;
        }

        int end = -1;
        for (int j = 0; j < n; j++) {
            if (previous[j] < INF && (end < 0 || previous[j] < previous[end])) end = j;
        }
        if (end < 0) return null;
        int[] days = new int[rounds];
        for (int k = rounds - 1; k >= 0; k--) {
            days[k] = free.get(end);
            end = from[k][end];
        }
        return days;
    }
}
