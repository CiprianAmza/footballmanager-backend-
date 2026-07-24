package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rebuilds all derived canonical player values for one independent weight set. */
public final class CalibrationInputFactory {
    public CanonicalRuntimeTeamInput build(CalibrationTeam raw, CanonicalScoringWeightSet weights) {
        List<CanonicalLineupPlayer> lineup = new ArrayList<>();
        Map<Long, com.footballmanagergamesimulator.compartment.TacticalContextInput> contexts = new LinkedHashMap<>();
        for (CalibrationPlayer player : raw.players()) {
            double suitability = roleSuitability(player, weights.match().getRoleWeights());
            PlayerCapabilitySnapshot capability = new PlayerCapabilitySnapshot(player.playerId(), player.position(),
                    player.positionFamiliarity(), player.roleFamiliarity(), player.leftFoot(), player.rightFoot(), false,
                    false, false);
            CanonicalLineupPlayer canonical = new CanonicalLineupPlayer(player.playerId(), player.position(), player.occurrence(),
                    player.role(), player.duty(), player.attributes(), player.fitness(), player.morale(), capability,
                    suitability, player.traits(), player.instruction());
            lineup.add(canonical);
            contexts.put(player.playerId(), player.context());
        }
        return new CanonicalRuntimeTeamInput(raw.mentality(), lineup, contexts);
    }

    private static double roleSuitability(CalibrationPlayer player, MatchEngineConfig.RoleWeights weights) {
        if (player.role() == null || player.roleAttributeWeights().isEmpty()) return 50.0;
        double weighted = player.roleAttributeWeights().entrySet().stream()
                .mapToDouble(e -> player.attributes().getOrDefault(e.getKey(), 1) * e.getValue()).sum();
        return Math.max(1.0, Math.min(100.0, weighted * weights.getSuitabilityScale()));
    }
}
