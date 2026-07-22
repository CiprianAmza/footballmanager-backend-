package com.footballmanagergamesimulator.animation;

/**
 * Explicit match period. Direction of play is derived from the period, never
 * inferred from the raw minute, so extra-time halves are modelled distinctly
 * from regulation halves even though their minutes overlap conceptually.
 */
public enum MatchPeriod {
    FIRST_HALF(true),
    SECOND_HALF(false),
    EXTRA_TIME_FIRST_HALF(true),
    EXTRA_TIME_SECOND_HALF(false);

    private final boolean homeAttacksRight;

    MatchPeriod(boolean homeAttacksRight) {
        this.homeAttacksRight = homeAttacksRight;
    }

    /**
     * Canonical attack direction for the home team in this period. Teams switch
     * ends between the two halves and again between the two extra-time halves;
     * regulation and extra time keep the same orientation per half index.
     */
    public boolean homeAttacksRight() {
        return homeAttacksRight;
    }

    public boolean isExtraTime() {
        return this == EXTRA_TIME_FIRST_HALF || this == EXTRA_TIME_SECOND_HALF;
    }
}
