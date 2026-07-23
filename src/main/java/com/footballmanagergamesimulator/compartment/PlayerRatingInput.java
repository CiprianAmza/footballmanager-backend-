package com.footballmanagergamesimulator.compartment;

import java.util.Map;

/** Immutable input to the pure contextual player-rating calculation. */
public record PlayerRatingInput(
        String position,
        String role,
        Duty duty,
        Map<PlayerAttribute, Integer> attributes,
        Map<PlayerAttribute, Double> contextCoefficients,
        double positionFamiliarity,
        double fitness,
        double morale,
        double roleSuitability) {

    public PlayerRatingInput {
        if (position == null || position.isBlank()) throw new IllegalArgumentException("position is required");
        if (duty == null) throw new IllegalArgumentException("duty is required");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        contextCoefficients = Map.copyOf(contextCoefficients == null ? Map.of() : contextCoefficients);
        role = role == null ? "" : role;
        requireFinite(positionFamiliarity, "positionFamiliarity");
        requireFinite(fitness, "fitness");
        requireFinite(morale, "morale");
        requireFinite(roleSuitability, "roleSuitability");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
