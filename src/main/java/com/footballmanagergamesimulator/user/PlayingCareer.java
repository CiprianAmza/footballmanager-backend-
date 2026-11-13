package com.footballmanagergamesimulator.user;

/**
 * The new manager's career as a footballer (design DESIGN_START_CARIERA §2.2, decided 2026-11-12): it sets his
 * starting reputation, on the clubs' scale (0–10,000).
 */
public enum PlayingCareer {
    NONE("No playing career", 1_000),
    AMATEUR("Amateur", 2_000),
    LOWER_LEAGUE("Lower league", 3_500),
    TOP_LEAGUE("Top league", 5_500),
    INTERNATIONAL("International", 8_000);

    private final String label;
    private final int startingReputation;

    PlayingCareer(String label, int startingReputation) {
        this.label = label;
        this.startingReputation = startingReputation;
    }

    public String label() {
        return label;
    }

    public int startingReputation() {
        return startingReputation;
    }
}
