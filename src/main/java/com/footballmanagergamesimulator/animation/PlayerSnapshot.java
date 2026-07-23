package com.footballmanagergamesimulator.animation;

/** Immutable snapshot of one participant actually on the pitch. */
public record PlayerSnapshot(
        long playerId,
        long teamId,
        String name,
        int shirtNumber,
        String tacticalPosition,
        String tacticalRole,
        double rating) {

    public PlayerSnapshot {
        if (playerId <= 0 || teamId <= 0) throw new IllegalArgumentException("player/team id must be positive");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("player name is required");
        if (shirtNumber < 0) throw new IllegalArgumentException("shirt number must be non-negative");
        if (tacticalPosition == null || tacticalPosition.isBlank())
            throw new IllegalArgumentException("tactical position is required");
        if (tacticalRole == null || tacticalRole.isBlank())
            throw new IllegalArgumentException("tactical role is required");
        if (!Double.isFinite(rating) || rating < 0)
            throw new IllegalArgumentException("rating must be finite and non-negative");
    }

    public boolean goalkeeper() {
        return "GK".equals(tacticalPosition);
    }
}
