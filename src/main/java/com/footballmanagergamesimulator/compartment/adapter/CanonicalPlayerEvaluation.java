package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.ContextualPlayerRating;
import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;

import java.util.Objects;

/** Explainable result of one canonical player-to-rating evaluation. */
public record CanonicalPlayerEvaluation(
        long playerId,
        PlayerPosition usedPosition,
        int occurrence,
        PlayerRole role,
        Duty duty,
        int positionFamiliarityRating,
        double positionFamiliarityFactor,
        int roleFamiliarityRating,
        double roleSuitability,
        int leftFootRating,
        int rightFootRating,
        boolean positionFallbackUsed,
        boolean roleFallbackUsed,
        boolean footFallbackUsed,
        ContextualPlayerRating rating) {

    public CanonicalPlayerEvaluation {
        if (playerId <= 0) throw new IllegalArgumentException("playerId must be positive");
        usedPosition = Objects.requireNonNull(usedPosition, "usedPosition");
        duty = Objects.requireNonNull(duty, "duty");
        rating = Objects.requireNonNull(rating, "rating");
        if (occurrence < 1) throw new IllegalArgumentException("occurrence must be >= 1");
        requireRating(positionFamiliarityRating, "positionFamiliarityRating");
        requireRating(roleFamiliarityRating, "roleFamiliarityRating");
        requireRating(leftFootRating, "leftFootRating");
        requireRating(rightFootRating, "rightFootRating");
        requireFinite(positionFamiliarityFactor, "positionFamiliarityFactor");
        if (positionFamiliarityFactor < 0.0 || positionFamiliarityFactor > 1.0) {
            throw new IllegalArgumentException("positionFamiliarityFactor must be in [0,1]");
        }
        requireFinite(roleSuitability, "roleSuitability");
        if (roleSuitability < 0.0 || roleSuitability > 100.0) {
            throw new IllegalArgumentException("roleSuitability must be in [0,100]");
        }
        double expectedPositionFactor = positionFamiliarityRating / 20.0;
        if (Double.compare(positionFamiliarityFactor, expectedPositionFactor) != 0) {
            throw new IllegalArgumentException("positionFamiliarityFactor must equal positionFamiliarityRating / 20.0");
        }
        if (role == null) {
            if (roleFamiliarityRating != 10) {
                throw new IllegalArgumentException("null role requires roleFamiliarityRating 10");
            }
            if (Double.compare(roleSuitability, 50.0) != 0) {
                throw new IllegalArgumentException("null role requires roleSuitability 50.0");
            }
            if (!roleFallbackUsed) {
                throw new IllegalArgumentException("null role requires roleFallbackUsed");
            }
        } else {
            new PositionRoleKey(usedPosition, role);
        }
        if (!usedPosition.code().equals(rating.position())) {
            throw new IllegalArgumentException("rating position does not match usedPosition");
        }
        if (rating.duty() != duty) {
            throw new IllegalArgumentException("rating duty does not match duty");
        }
        String expectedRole = role == null ? "" : role.displayName();
        if (!expectedRole.equals(rating.role())) {
            throw new IllegalArgumentException("rating role does not match role");
        }
    }

    private static void requireRating(int value, String field) {
        if (value < 1 || value > 20) throw new IllegalArgumentException(field + " must be in [1,20]");
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}
