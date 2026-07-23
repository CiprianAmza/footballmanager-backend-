package com.footballmanagergamesimulator.animation;

/** Optional cosmetic context. Canonical match facts never depend on it. */
public record TacticalContext(
        String attackingMentality,
        String defendingMentality,
        double attackingWidth,
        double defensiveLine) {

    public TacticalContext {
        if (!Double.isFinite(attackingWidth) || !Double.isFinite(defensiveLine))
            throw new IllegalArgumentException("tactical values must be finite");
    }
}
