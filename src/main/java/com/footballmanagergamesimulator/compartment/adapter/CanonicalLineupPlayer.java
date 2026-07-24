package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.PlayerTrait;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, domain-free input for one canonical lineup player evaluation. */
public record CanonicalLineupPlayer(
        long playerId,
        PlayerPosition usedPosition,
        int occurrence,
        PlayerRole role,
        Duty duty,
        Map<PlayerAttribute, Integer> attributes,
        double fitness,
        double morale,
        PlayerCapabilitySnapshot capability,
        double roleSuitability,
        Set<PlayerTrait> traits,
        ForwardInstruction forwardInstruction) {

    public CanonicalLineupPlayer {
        if (playerId <= 0) throw new IllegalArgumentException("playerId must be positive");
        usedPosition = Objects.requireNonNull(usedPosition, "usedPosition");
        duty = Objects.requireNonNull(duty, "duty");
        forwardInstruction = Objects.requireNonNull(forwardInstruction, "forwardInstruction");
        if (occurrence < 1) throw new IllegalArgumentException("occurrence must be >= 1");
        capability = Objects.requireNonNull(capability, "capability");
        if (capability.playerId() != playerId) {
            throw new IllegalArgumentException("capability player id does not match lineup player");
        }
        requireFinite(fitness, "fitness");
        requireFinite(morale, "morale");
        requireFinite(roleSuitability, "roleSuitability");
        if (roleSuitability < 0.0 || roleSuitability > 100.0) {
            throw new IllegalArgumentException("roleSuitability must be in [0,100]");
        }
        if (role != null) {
            new PositionRoleKey(usedPosition, role);
        } else {
            roleSuitability = 50.0;
        }
        attributes = immutableAttributes(attributes);
        traits = immutableTraits(traits);
    }

    private static Map<PlayerAttribute, Integer> immutableAttributes(Map<PlayerAttribute, Integer> source) {
        Objects.requireNonNull(source, "attributes");
        EnumMap<PlayerAttribute, Integer> copy = new EnumMap<>(PlayerAttribute.class);
        for (Map.Entry<PlayerAttribute, Integer> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("attributes cannot contain null keys or values");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Set<PlayerTrait> immutableTraits(Set<PlayerTrait> source) {
        Objects.requireNonNull(source, "traits");
        EnumSet<PlayerTrait> copy = EnumSet.noneOf(PlayerTrait.class);
        for (PlayerTrait trait : source) {
            if (trait == null) throw new IllegalArgumentException("traits cannot contain null values");
            copy.add(trait);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}
