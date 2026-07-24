package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.Objects;

/** Pure Phase 6 resolver for persisted player capability values and legacy fallbacks. */
public final class PlayerCapabilityResolver {
    private final MatchEngineConfig matchEngineConfig;

    public PlayerCapabilityResolver(MatchEngineConfig matchEngineConfig) {
        this.matchEngineConfig = Objects.requireNonNull(matchEngineConfig, "matchEngineConfig");
    }

    public int fallbackPositionFamiliarity(String naturalPosition, PlayerPosition usedPosition) {
        Objects.requireNonNull(usedPosition, "usedPosition");
        PlayerPosition natural = PlayerPosition.parse(naturalPosition).orElse(null);
        if (natural == usedPosition) {
            return 20;
        }
        double factor = matchEngineConfig.getPlayerValue().familiarity(
                natural == null ? null : natural.code(), usedPosition.code());
        return clampToFamiliarity((int) Math.round(factor * 20.0));
    }

    public int positionFamiliarityOrFallback(PlayerCapabilitySnapshot snapshot, PlayerPosition usedPosition) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(usedPosition, "usedPosition");
        Integer persistent = snapshot.positionFamiliarity().get(usedPosition);
        if (persistent != null) {
            return persistent;
        }
        PlayerPosition natural = snapshot.primaryPosition();
        if (natural != null) {
            Integer primary = snapshot.positionFamiliarity().get(natural);
            if (natural == usedPosition && primary != null) {
                return primary;
            }
        }
        return fallbackPositionFamiliarity(natural == null ? null : natural.code(), usedPosition);
    }

    public int roleFamiliarityOrFallback(PlayerCapabilitySnapshot snapshot,
                                         PlayerPosition position,
                                         PlayerRole role) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(position, "position");
        if (role == null) {
            return 10;
        }
        PositionRoleKey key = new PositionRoleKey(position, role);
        return snapshot.roleFamiliarity().getOrDefault(key, 10);
    }

    private static int clampToFamiliarity(int value) {
        return Math.max(1, Math.min(20, value));
    }
}
