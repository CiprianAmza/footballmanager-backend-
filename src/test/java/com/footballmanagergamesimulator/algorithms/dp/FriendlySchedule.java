package com.footballmanagergamesimulator.algorithms.dp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Weighted interval scheduling on a pre-season calendar: each friendly occupies a day
 * range and is worth a fixed amount, no two accepted friendlies may overlap, and the
 * club wants the highest total value.
 *
 * <p>This is the DP where the transition is not "the previous index" but "the last
 * offer that ends before this one starts" - the state still moves one step at a time,
 * the jump does not.
 */
public final class FriendlySchedule {

    /** startDay and endDay are inclusive day numbers inside the pre-season window. */
    public record Offer(String opponent, int startDay, int endDay, int valueK) {
    }

    public record Plan(List<String> accepted, int totalValueK) {
    }

    private FriendlySchedule() {
    }

    public static Plan best(List<Offer> offers) {
        List<Offer> sorted = new ArrayList<>(offers);
        sorted.sort(Comparator.comparingInt(Offer::endDay));
        int n = sorted.size();

        // previous[i] = index of the last offer that finishes strictly before offer i starts.
        int[] previous = new int[n];
        for (int i = 0; i < n; i++) {
            previous[i] = -1;
            for (int j = i - 1; j >= 0; j--) {
                if (sorted.get(j).endDay() < sorted.get(i).startDay()) {
                    previous[i] = j;
                    break;
                }
            }
        }

        int[] dp = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            Offer offer = sorted.get(i - 1);
            int skip = dp[i - 1];
            int take = offer.valueK() + dp[previous[i - 1] + 1];
            dp[i] = Math.max(skip, take);
        }

        List<String> accepted = new ArrayList<>();
        int i = n;
        while (i > 0) {
            Offer offer = sorted.get(i - 1);
            int take = offer.valueK() + dp[previous[i - 1] + 1];
            if (take > dp[i - 1]) {
                accepted.add(offer.opponent());
                i = previous[i - 1] + 1;
            } else {
                i--;
            }
        }
        return new Plan(List.copyOf(accepted), dp[n]);
    }

    /** Take every offer that still fits, earliest finish first. Fast, and not always optimal. */
    public static Plan greedyEarliestFinish(List<Offer> offers) {
        List<Offer> sorted = new ArrayList<>(offers);
        sorted.sort(Comparator.comparingInt(Offer::endDay));

        List<String> accepted = new ArrayList<>();
        int lastEnd = Integer.MIN_VALUE;
        int total = 0;
        for (Offer offer : sorted) {
            if (offer.startDay() > lastEnd) {
                accepted.add(offer.opponent());
                lastEnd = offer.endDay();
                total += offer.valueK();
            }
        }
        return new Plan(List.copyOf(accepted), total);
    }
}
