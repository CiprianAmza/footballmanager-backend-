package com.footballmanagergamesimulator.service;

import java.util.Random;

/**
 * How rarely an attribute climbs past the ordinary maximum (19). Any growth can take an
 * attribute to 20 and beyond, but each level is much harder to reach than the previous one:
 *
 * <pre>
 *   20 → 1 in 100        23 → 1 in 10,000
 *   21 → 1 in 500        24 → 1 in 15,000
 *   22 → 1 in 5,000      25 → 1 in 20,000, then +5,000 per further level
 * </pre>
 *
 * Levels up to 19 need no roll.
 */
public final class AttributeCeilingOdds {

    /** The highest value reachable without a roll. */
    public static final int ORDINARY_MAX = 19;

    private AttributeCeilingOdds() {
    }

    /** "1 in N" for reaching {@code level}; 1 for any level up to {@link #ORDINARY_MAX}. */
    public static long oneIn(int level) {
        if (level <= ORDINARY_MAX) return 1;
        return switch (level) {
            case 20 -> 100;
            case 21 -> 500;
            case 22 -> 5_000;
            default -> 10_000L + (level - 23) * 5_000L;
        };
    }

    /** Rolls whether an attribute that would grow to {@code level} actually gets there. */
    public static boolean reaches(int level, Random rng) {
        long oneIn = oneIn(level);
        return oneIn <= 1 || rng.nextLong(oneIn) == 0;
    }
}
