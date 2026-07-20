package com.footballmanagergamesimulator.config;

import org.springframework.stereotype.Component;

/**
 * Single source of truth for League of Champions access by country rank.
 * Positions are consumed in this order: direct group places, qualifying round
 * places, then preliminary-round places.
 */
@Component
public class EuropeanQualificationPolicy {

    private static final int[] DIRECT =      {3, 3, 2, 2, 1, 1, 0};
    private static final int[] QUALIFYING =  {1, 1, 1, 1, 2, 1, 0};
    private static final int[] PRELIMINARY = {0, 0, 0, 0, 0, 0, 2};

    public int directForRank(int rank) { return value(DIRECT, rank); }
    public int qualifyingForRank(int rank) { return value(QUALIFYING, rank); }
    public int preliminaryForRank(int rank) { return value(PRELIMINARY, rank); }

    public int totalForRank(int rank) {
        return directForRank(rank) + qualifyingForRank(rank) + preliminaryForRank(rank);
    }

    public int totalEntrants() {
        int total = 0;
        for (int rank = 1; rank <= DIRECT.length; rank++) total += totalForRank(rank);
        return total;
    }

    public int directEntrants() { return sum(DIRECT); }
    public int qualifyingEntrants() { return sum(QUALIFYING); }
    public int preliminaryEntrants() { return sum(PRELIMINARY); }

    private int value(int[] values, int rank) {
        return rank >= 1 && rank <= values.length ? values[rank - 1] : 0;
    }

    private int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }
}
