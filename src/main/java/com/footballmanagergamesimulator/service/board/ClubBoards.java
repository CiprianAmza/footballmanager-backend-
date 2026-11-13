package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.DirectorPersonality;

/**
 * Pure rules for setting up club boards (design DESIGN_START_CARIERA §5, decided 2026-11-12): about 30% of clubs
 * have a general director, bigger clubs more often; his personality is neutral 40%, supportive 20%, loyal to the
 * chairman 20%, ambitious 20%.
 */
public final class ClubBoards {

    public static final double DIRECTOR_SHARE = 0.30;

    private ClubBoards() {
    }

    /**
     * Chance that the club ranked {@code rank} (0 = most famous) of {@code clubs} has a director: falls linearly
     * from {@code 2 x DIRECTOR_SHARE} at the top to 0 at the bottom, so it averages {@link #DIRECTOR_SHARE}.
     */
    public static double directorChance(int rank, int clubs) {
        if (clubs <= 1) return DIRECTOR_SHARE;
        double top = 1.0 - (double) rank / (clubs - 1);
        return 2 * DIRECTOR_SHARE * top;
    }

    /** Personality from a uniform draw in [0, 1). */
    public static DirectorPersonality personality(double roll) {
        if (roll < 0.40) return DirectorPersonality.NEUTRAL;
        if (roll < 0.60) return DirectorPersonality.SUPPORTIVE;
        if (roll < 0.80) return DirectorPersonality.OWNER_LOYAL;
        return DirectorPersonality.AMBITIOUS;
    }
}
