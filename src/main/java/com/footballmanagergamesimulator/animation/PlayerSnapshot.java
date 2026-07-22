package com.footballmanagergamesimulator.animation;

/**
 * Immutable snapshot of one player on the pitch at the canonical moment.
 * Only players present in a {@link MatchMomentSpec} snapshot may ever appear
 * in the produced animation.
 *
 * @param position tactical position code (GK, DC, DL, DR, DM, MC, ML, MR,
 *                 AMC, AML, AMR, ST)
 */
public record PlayerSnapshot(
        long playerId,
        String name,
        int shirtNumber,
        String position,
        double rating) {

    public PlayerSnapshot {
        if (playerId <= 0) throw new IllegalArgumentException("playerId must be positive");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("player name is required");
        if (shirtNumber < 0) throw new IllegalArgumentException("shirtNumber must be >= 0");
        if (position == null || position.isBlank()) {
            throw new IllegalArgumentException("tactical position is required");
        }
        if (!Double.isFinite(rating) || rating < 0) {
            throw new IllegalArgumentException("rating must be a finite non-negative number");
        }
    }

    public boolean isGoalkeeper() {
        return "GK".equals(position);
    }
}
