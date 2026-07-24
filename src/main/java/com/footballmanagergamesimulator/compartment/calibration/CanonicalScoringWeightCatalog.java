package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, lexicographically ordered view of all bound canonical leaves. */
public final class CanonicalScoringWeightCatalog {
    private final Map<String, CanonicalScoringWeightKey> byPath;

    private CanonicalScoringWeightCatalog(Map<String, CanonicalScoringWeightKey> byPath) {
        this.byPath = Collections.unmodifiableMap(new LinkedHashMap<>(byPath));
    }

    public static CanonicalScoringWeightCatalog from(CompartmentEngineConfig compartment, MatchEngineConfig match) {
        if (compartment == null || match == null) throw new NullPointerException("configs");
        Map<String, CanonicalScoringWeightKey> leaves = new java.util.TreeMap<>();
        add(leaves, "compartment.rating.attribute-min", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getAttributeMin(), CanonicalScoringWeightKey.Type.INTEGER, "rating normalization");
        add(leaves, "compartment.rating.attribute-max", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getAttributeMax(), CanonicalScoringWeightKey.Type.INTEGER, "rating normalization");
        add(leaves, "compartment.rating.score-scale", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getScoreScale(), CanonicalScoringWeightKey.Type.CONTINUOUS, "rating normalization");
        add(leaves, "compartment.rating.context-coefficient-min", CanonicalScoringWeightKey.Category.CONTEXT, compartment.getRating().getContextCoefficientMin(), CanonicalScoringWeightKey.Type.CONTINUOUS, "context coefficient clamp");
        add(leaves, "compartment.rating.context-coefficient-max", CanonicalScoringWeightKey.Category.CONTEXT, compartment.getRating().getContextCoefficientMax(), CanonicalScoringWeightKey.Type.CONTINUOUS, "context coefficient clamp");
        compartment.getContextRules().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row ->
                row.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                        add(leaves, "compartment.context-rules." + row.getKey() + "." + e.getKey().name(), CanonicalScoringWeightKey.Category.CONTEXT,
                                e.getValue(), CanonicalScoringWeightKey.Type.CONTINUOUS, "ContextCoefficientMapper")));
        compartment.getCompartments().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                e.getValue().getAttributes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(a ->
                        add(leaves, "compartment.compartments." + e.getKey().name() + ".attributes." + a.getKey().name(), CanonicalScoringWeightKey.Category.COMPARTMENT,
                                a.getValue(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator")));
        compartment.getPositionCompartmentOverrides().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                e.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(c ->
                        c.getValue().getAttributes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(a ->
                                add(leaves, "compartment.position-overrides." + e.getKey() + "." + c.getKey().name() + "." + a.getKey().name(), CanonicalScoringWeightKey.Category.POSITION,
                                        a.getValue(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator"))));
        compartment.getPositions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> addMultipliers(leaves, "compartment.positions." + e.getKey(), e.getValue(), CanonicalScoringWeightKey.Category.POSITION));
        compartment.getRoles().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> addMultipliers(leaves, "compartment.roles." + e.getKey().name(), e.getValue(), CanonicalScoringWeightKey.Category.ROLE));
        compartment.getDuties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> addMultipliers(leaves, "compartment.duties." + e.getKey().name(), e.getValue(), CanonicalScoringWeightKey.Category.DUTY));
        compartment.getMentalities().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            add(leaves, "compartment.mentalities." + e.getKey().name() + ".midfield-to-attack", CanonicalScoringWeightKey.Category.MENTALITY, e.getValue().getMidfieldToAttack(), CanonicalScoringWeightKey.Type.CONTINUOUS, "MentalityRule");
            add(leaves, "compartment.mentalities." + e.getKey().name() + ".midfield-to-defense", CanonicalScoringWeightKey.Category.MENTALITY, e.getValue().getMidfieldToDefense(), CanonicalScoringWeightKey.Type.CONTINUOUS, "MentalityRule");
            add(leaves, "compartment.mentalities." + e.getKey().name() + ".openness", CanonicalScoringWeightKey.Category.MENTALITY, e.getValue().getOpenness(), CanonicalScoringWeightKey.Type.CONTINUOUS, "MentalityRule");
        });
        add(leaves, "compartment.exposure.coverage-reduction", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getCoverageReduction(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.exposure.second-dm-weight", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getSecondDmWeight(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.exposure.cb-recovery-pace-cap", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getCbRecoveryPaceCap(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.exposure.penalty-strength", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getPenaltyStrength(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.exposure.penalty-exponent", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getPenaltyExponent(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.probability.matchup-exponent", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getMatchupExponent(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "compartment.probability.home-advantage", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getHomeAdvantage(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "compartment.probability.gamma-shape", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getGammaShape(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "compartment.probability.goal-cap", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getGoalCap(), CanonicalScoringWeightKey.Type.INTEGER, "goal probability");
        add(leaves, "match.player-value.morale-neutral", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getMoraleNeutral(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        add(leaves, "match.player-value.morale-slope", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getMoraleSlope(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        add(leaves, "match.role-weights.overall-blend", CanonicalScoringWeightKey.Category.ROLE_FIT, match.getRoleWeights().getOverallBlend(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerRoleService");
        add(leaves, "match.role-weights.role-blend", CanonicalScoringWeightKey.Category.ROLE_FIT, match.getRoleWeights().getRoleBlend(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerRoleService");
        add(leaves, "match.role-weights.suitability-scale", CanonicalScoringWeightKey.Category.ROLE_FIT, match.getRoleWeights().getSuitabilityScale(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerRoleService");
        add(leaves, "match.instruction-weights.bonus-scale", CanonicalScoringWeightKey.Category.INSTRUCTION, match.getInstructionWeights().getBonusScale(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerInstructionService");
        add(leaves, "match.instruction-weights.conflict-penalty", CanonicalScoringWeightKey.Category.INSTRUCTION, match.getInstructionWeights().getConflictPenalty(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerInstructionService");
        return new CanonicalScoringWeightCatalog(leaves);
    }

    private static void addMultipliers(Map<String, CanonicalScoringWeightKey> leaves, String path, CompartmentEngineConfig.CompartmentMultipliers m, CanonicalScoringWeightKey.Category category) {
        add(leaves, path + ".attack", category, m.getAttack(), CanonicalScoringWeightKey.Type.CONTINUOUS, "compartment multipliers");
        add(leaves, path + ".midfield", category, m.getMidfield(), CanonicalScoringWeightKey.Type.CONTINUOUS, "compartment multipliers");
        add(leaves, path + ".defense", category, m.getDefense(), CanonicalScoringWeightKey.Type.CONTINUOUS, "compartment multipliers");
    }
    private static void add(Map<String, CanonicalScoringWeightKey> leaves, String path, CanonicalScoringWeightKey.Category category, Object value, CanonicalScoringWeightKey.Type type, String consumer) {
        if (leaves.put(path, new CanonicalScoringWeightKey(path, category, value, type, CanonicalScoringWeightKey.PerturbationMode.DIRECT, consumer)) != null) throw new IllegalArgumentException("duplicate weight: " + path);
    }
    public List<CanonicalScoringWeightKey> leafWeights() { return List.copyOf(byPath.values()); }
    public CanonicalScoringWeightKey get(String path) { return byPath.get(path); }
    public CanonicalScoringWeightKey require(String path) { if (!byPath.containsKey(path)) throw new IllegalArgumentException("unknown weight: " + path); return byPath.get(path); }
    public int size() { return byPath.size(); }
}
