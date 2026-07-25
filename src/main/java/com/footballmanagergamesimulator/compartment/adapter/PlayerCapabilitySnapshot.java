package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerPosition;

import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;

public record PlayerCapabilitySnapshot(
        long playerId,
        PlayerPosition primaryPosition,
        Map<PlayerPosition, Integer> positionFamiliarity,
        Map<PositionRoleKey, Integer> roleFamiliarity,
        int leftFootRating,
        int rightFootRating,
        boolean positionFallbackUsed,
        boolean roleFallbackUsed,
        boolean footFallbackUsed) {

    public PlayerCapabilitySnapshot {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        EnumMap<PlayerPosition, Integer> positions = new EnumMap<>(PlayerPosition.class);
        if (positionFamiliarity != null) positions.putAll(positionFamiliarity);
        positionFamiliarity = Collections.unmodifiableMap(positions);
        roleFamiliarity = Collections.unmodifiableMap(new LinkedHashMap<>(
                roleFamiliarity == null ? Map.of() : roleFamiliarity));
        requireRating(leftFootRating, "leftFootRating");
        requireRating(rightFootRating, "rightFootRating");
    }

    public Optional<PlayerPosition> primaryPositionOptional() {
        return Optional.ofNullable(primaryPosition);
    }

    private static void requireRating(int value, String field) {
        if (value < 1 || value > 20) {
            throw new IllegalArgumentException(field + " must be in [1,20]");
        }
    }
}
