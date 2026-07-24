package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.service.PlayerRoleService;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rebuilds all derived canonical player values for one independent weight set. */
public final class CalibrationInputFactory {
    private final PlayerRoleService roleService = new PlayerRoleService();
    public CanonicalRuntimeTeamInput build(CalibrationTeam raw, CanonicalScoringWeightSet weights) {
        List<CanonicalLineupPlayer> lineup = new ArrayList<>();
        Map<Long, com.footballmanagergamesimulator.compartment.TacticalContextInput> contexts = new LinkedHashMap<>();
        for (CalibrationPlayer player : raw.players()) {
            double suitability = roleSuitability(player, weights.match());
            PlayerCapabilitySnapshot capability = new PlayerCapabilitySnapshot(player.playerId(), player.primaryPosition(),
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

    private double roleSuitability(CalibrationPlayer player, MatchEngineConfig match) {
        if (player.role() == null || player.roleAttributeWeights().isEmpty()) return 50.0;
        Map<PlayerAttribute, Double> roleWeights = new EnumMap<>(PlayerAttribute.class);
        Map<String, Double> namedWeights = new LinkedHashMap<>();
        {
            String family = switch (player.position()) {
                case GK -> "GK"; case DC -> "DC"; case DL, WBL -> "DL"; case DR, WBR -> "DR";
                case DM, MC, AMC -> "MC"; case ML, AML -> "ML"; case MR, AMR -> "MR"; case ST -> "ST";
            };
            roleService.getRolesForPosition(family).stream()
                    .filter(role -> role.name.equals(player.role().displayName()))
                    .findFirst().ifPresent(role -> role.keyAttributes.forEach((name, value) ->
                            com.footballmanagergamesimulator.service.PlayerSkillsService.GETTER_MAP.entrySet().stream()
                                    .filter(e -> e.getKey().equals(name)).findFirst().ifPresent(e -> {
                            PlayerAttribute attribute = attribute(name);
                            if (attribute != null) roleWeights.put(attribute, value);
                            else namedWeights.put(name, value);
                                    })));
        }
        Map<String, Double> overrides = match.getRoleWeights().attributesFor(player.role().displayName());
        if (overrides != null) overrides.forEach((name, value) -> {
            PlayerAttribute attribute = attribute(name);
            if (attribute != null) roleWeights.put(attribute, value);
            else namedWeights.put(name, value);
        });
        if (roleWeights.isEmpty()) roleWeights.putAll(player.roleAttributeWeights());
        double weighted = roleWeights.entrySet().stream()
                .mapToDouble(e -> player.attributes().getOrDefault(e.getKey(), 1) * e.getValue()).sum();
        weighted += namedWeights.entrySet().stream()
                .mapToDouble(e -> player.namedAttributes().getOrDefault(e.getKey(), 1) * e.getValue()).sum();
        return Math.max(1.0, Math.min(100.0, weighted * match.getRoleWeights().getSuitabilityScale()));
    }

    private static PlayerAttribute attribute(String name) {
        try { return PlayerAttribute.valueOf(name.replace(' ', '_').toUpperCase()); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
