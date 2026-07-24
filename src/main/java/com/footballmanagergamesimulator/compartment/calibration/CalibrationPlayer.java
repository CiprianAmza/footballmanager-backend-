package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Duty;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Raw, immutable calibration input. It deliberately contains no JPA/domain entity. */
public record CalibrationPlayer(
        long playerId,
        PlayerPosition position,
        int occurrence,
        PlayerRole role,
        Duty duty,
        Map<PlayerAttribute, Integer> attributes,
        Map<PlayerAttribute, Double> roleAttributeWeights,
        double fitness,
        double morale,
        Map<PlayerPosition, Integer> positionFamiliarity,
        Map<com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey, Integer> roleFamiliarity,
        int leftFoot,
        int rightFoot,
        Set<PlayerTrait> traits,
        ForwardInstruction instruction,
        TacticalContextInput context,
        PlayerPosition primaryPosition,
        Map<String, Integer> namedAttributes) {
    public CalibrationPlayer(long playerId, PlayerPosition position, int occurrence, PlayerRole role, Duty duty,
                             Map<PlayerAttribute, Integer> attributes, Map<PlayerAttribute, Double> roleAttributeWeights,
                             double fitness, double morale, Map<PlayerPosition, Integer> positionFamiliarity,
                             Map<com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey, Integer> roleFamiliarity,
                             int leftFoot, int rightFoot, Set<PlayerTrait> traits, ForwardInstruction instruction,
                             TacticalContextInput context) {
        this(playerId, position, occurrence, role, duty, attributes, roleAttributeWeights, fitness, morale,
                positionFamiliarity, roleFamiliarity, leftFoot, rightFoot, traits, instruction, context, position, Map.of());
    }
    public CalibrationPlayer {
        if (playerId <= 0 || occurrence < 1) throw new IllegalArgumentException("invalid player identity");
        position = Objects.requireNonNull(position, "position");
        duty = Objects.requireNonNull(duty, "duty");
        attributes = Map.copyOf(attributes);
        roleAttributeWeights = Map.copyOf(roleAttributeWeights == null ? Map.of() : roleAttributeWeights);
        positionFamiliarity = Map.copyOf(positionFamiliarity == null ? Map.of() : positionFamiliarity);
        roleFamiliarity = Map.copyOf(roleFamiliarity == null ? Map.of() : roleFamiliarity);
        traits = traits == null ? Set.of() : Set.copyOf(traits);
        instruction = Objects.requireNonNull(instruction, "instruction");
        context = Objects.requireNonNull(context, "context");
        primaryPosition = Objects.requireNonNull(primaryPosition, "primaryPosition");
        namedAttributes = Map.copyOf(namedAttributes == null ? Map.of() : namedAttributes);
        if (leftFoot < 1 || leftFoot > 20 || rightFoot < 1 || rightFoot > 20) {
            throw new IllegalArgumentException("foot ratings must be in [1,20]");
        }
        if (!Double.isFinite(fitness) || !Double.isFinite(morale)) throw new IllegalArgumentException("fitness/morale must be finite");
        if (role != null && role != PlayerRole.SHADOW_STRIKER) {
            new com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey(position, role);
        }
    }
}
