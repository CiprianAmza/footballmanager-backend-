package com.footballmanagergamesimulator.compartment;

import java.util.Map;

/**
 * Immutable input to the pure contextual player-rating calculation. {@code position} is the
 * position played in the tactic; {@code naturalPosition} is the player's own, the one his
 * special profile (level 1, {@link PlayerSpecialProfile}) is read at.
 */
public record PlayerRatingInput(
        String position,
        String role,
        Duty duty,
        Map<PlayerAttribute, Integer> attributes,
        Map<PlayerAttribute, Double> contextCoefficients,
        double positionFamiliarity,
        double fitness,
        double morale,
        double roleSuitability,
        String naturalPosition) {

    /** A player at his natural position. */
    public PlayerRatingInput(String position, String role, Duty duty, Map<PlayerAttribute, Integer> attributes,
                             Map<PlayerAttribute, Double> contextCoefficients, double positionFamiliarity,
                             double fitness, double morale, double roleSuitability) {
        this(position, role, duty, attributes, contextCoefficients, positionFamiliarity, fitness, morale,
                roleSuitability, position);
    }

    public PlayerRatingInput {
        if (position == null || position.isBlank()) throw new IllegalArgumentException("position is required");
        if (naturalPosition == null || naturalPosition.isBlank()) naturalPosition = position;
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
