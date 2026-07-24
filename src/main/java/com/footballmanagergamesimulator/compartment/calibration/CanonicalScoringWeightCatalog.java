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
        add(leaves, "compartment.rating.context-factor-min", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getContextFactorMin(), CanonicalScoringWeightKey.Type.CONTINUOUS, "rating normalization");
        add(leaves, "compartment.rating.context-factor-max", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getContextFactorMax(), CanonicalScoringWeightKey.Type.CONTINUOUS, "rating normalization");
        add(leaves, "compartment.rating.total-context-min", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getTotalContextMin(), CanonicalScoringWeightKey.Type.CONTINUOUS, "rating normalization");
        add(leaves, "compartment.rating.total-context-max", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getTotalContextMax(), CanonicalScoringWeightKey.Type.CONTINUOUS, "rating normalization");
        add(leaves, "compartment.rating.context-coefficient-min", CanonicalScoringWeightKey.Category.CONTEXT, compartment.getRating().getContextCoefficientMin(), CanonicalScoringWeightKey.Type.CONTINUOUS, "context coefficient clamp");
        add(leaves, "compartment.rating.context-coefficient-max", CanonicalScoringWeightKey.Category.CONTEXT, compartment.getRating().getContextCoefficientMax(), CanonicalScoringWeightKey.Type.CONTINUOUS, "context coefficient clamp");
        add(leaves, "compartment.rating.role-fit-base", CanonicalScoringWeightKey.Category.ROLE_FIT, compartment.getRating().getRoleFitBase(), CanonicalScoringWeightKey.Type.CONTINUOUS, "role fit");
        add(leaves, "compartment.rating.role-fit-range", CanonicalScoringWeightKey.Category.ROLE_FIT, compartment.getRating().getRoleFitRange(), CanonicalScoringWeightKey.Type.CONTINUOUS, "role fit");
        add(leaves, "compartment.rating.fitness-floor", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getFitnessFloor(), CanonicalScoringWeightKey.Type.CONTINUOUS, "fitness");
        add(leaves, "compartment.rating.morale-neutral", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getMoraleNeutral(), CanonicalScoringWeightKey.Type.CONTINUOUS, "morale");
        add(leaves, "compartment.rating.morale-slope", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getMoraleSlope(), CanonicalScoringWeightKey.Type.CONTINUOUS, "morale");
        add(leaves, "compartment.rating.default-position-multiplier", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getDefaultPositionMultiplier(), CanonicalScoringWeightKey.Type.CONTINUOUS, "position fallback");
        add(leaves, "compartment.rating.default-role-multiplier", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getDefaultRoleMultiplier(), CanonicalScoringWeightKey.Type.CONTINUOUS, "role fallback");
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
            add(leaves, "compartment.mentalities." + e.getKey().name() + ".transfer-from", CanonicalScoringWeightKey.Category.MENTALITY, e.getValue().getTransferFrom().name(), CanonicalScoringWeightKey.Type.DISCRETE, "MentalityRule");
            add(leaves, "compartment.mentalities." + e.getKey().name() + ".transfer-to", CanonicalScoringWeightKey.Category.MENTALITY, e.getValue().getTransferTo().name(), CanonicalScoringWeightKey.Type.DISCRETE, "MentalityRule");
            add(leaves, "compartment.mentalities." + e.getKey().name() + ".transfer-share", CanonicalScoringWeightKey.Category.MENTALITY, e.getValue().getTransferShare(), CanonicalScoringWeightKey.Type.CONTINUOUS, "MentalityRule");
            add(leaves, "compartment.mentalities." + e.getKey().name() + ".openness", CanonicalScoringWeightKey.Category.MENTALITY, e.getValue().getOpenness(), CanonicalScoringWeightKey.Type.CONTINUOUS, "MentalityRule");
        });
        compartment.getWorkRate().getTraits().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> addWorkRule(leaves, "compartment.work-rate.traits." + e.getKey().name(), e.getValue()));
        compartment.getWorkRate().getInstructions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> addWorkRule(leaves, "compartment.work-rate.instructions." + e.getKey().name(), e.getValue()));
        compartment.getExposure().getZoneWeights().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                add(leaves, "compartment.exposure.zone-weights." + e.getKey(), CanonicalScoringWeightKey.Category.EXPOSURE, e.getValue(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure"));
        add(leaves, "compartment.exposure.coverage-reduction", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getCoverageReduction(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.exposure.second-dm-weight", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getSecondDmWeight(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.exposure.cb-recovery-pace-cap", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getCbRecoveryPaceCap(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.exposure.penalty-strength", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getPenaltyStrength(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.exposure.penalty-exponent", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getExposure().getPenaltyExponent(), CanonicalScoringWeightKey.Type.CONTINUOUS, "defensive exposure");
        add(leaves, "compartment.probability.matchup-exponent", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getMatchupExponent(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "compartment.probability.home-advantage", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getHomeAdvantage(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "compartment.probability.gamma-shape", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getGammaShape(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "compartment.probability.goal-cap", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getGoalCap(), CanonicalScoringWeightKey.Type.INTEGER, "goal probability");
        add(leaves, "compartment.probability.extra-time-scale", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getExtraTimeScale(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "compartment.probability.interval-lower-quantile", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getIntervalLowerQuantile(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "compartment.probability.interval-upper-quantile", CanonicalScoringWeightKey.Category.PROBABILITY, compartment.getProbability().getIntervalUpperQuantile(), CanonicalScoringWeightKey.Type.CONTINUOUS, "goal probability");
        add(leaves, "match.player-value.morale-neutral", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getMoraleNeutral(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        add(leaves, "match.player-value.morale-slope", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getMoraleSlope(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        add(leaves, "match.player-value.scale-multiplier", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getScaleMultiplier(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        add(leaves, "match.player-value.rating-floor", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getRatingFloor(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        add(leaves, "match.player-value.rating-ceil", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getRatingCeil(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        add(leaves, "match.player-value.fitness-floor", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getFitnessFloor(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        add(leaves, "match.player-value.default-familiarity-penalty", CanonicalScoringWeightKey.Category.PLAYER_VALUE, match.getPlayerValue().getDefaultFamiliarityPenalty(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerValue");
        addNested(leaves, "match.player-value.weights", match.getPlayerValue().getWeights(), CanonicalScoringWeightKey.Category.PLAYER_VALUE, "PlayerValue");
        addNested(leaves, "match.player-value.familiarity-penalty", match.getPlayerValue().getFamiliarityPenalty(), CanonicalScoringWeightKey.Category.PLAYER_VALUE, "PlayerValue");
        add(leaves, "match.role-weights.overall-blend", CanonicalScoringWeightKey.Category.ROLE_FIT, match.getRoleWeights().getOverallBlend(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerRoleService");
        add(leaves, "match.role-weights.role-blend", CanonicalScoringWeightKey.Category.ROLE_FIT, match.getRoleWeights().getRoleBlend(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerRoleService");
        add(leaves, "match.role-weights.suitability-scale", CanonicalScoringWeightKey.Category.ROLE_FIT, match.getRoleWeights().getSuitabilityScale(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerRoleService");
        addNested(leaves, "match.role-weights.attributes", match.getRoleWeights().getAttributes(), CanonicalScoringWeightKey.Category.ROLE_FIT, "PlayerRoleService");
        add(leaves, "match.instruction-weights.bonus-scale", CanonicalScoringWeightKey.Category.INSTRUCTION, match.getInstructionWeights().getBonusScale(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerInstructionService");
        add(leaves, "match.instruction-weights.conflict-penalty", CanonicalScoringWeightKey.Category.INSTRUCTION, match.getInstructionWeights().getConflictPenalty(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerInstructionService");
        add(leaves, "match.instruction-weights.clamp-min", CanonicalScoringWeightKey.Category.INSTRUCTION, match.getInstructionWeights().getClampMin(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerInstructionService");
        add(leaves, "match.instruction-weights.clamp-max", CanonicalScoringWeightKey.Category.INSTRUCTION, match.getInstructionWeights().getClampMax(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerInstructionService");
        match.getInstructionWeights().getBonuses().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            add(leaves, "match.instruction-weights.bonuses." + e.getKey() + ".base", CanonicalScoringWeightKey.Category.INSTRUCTION, e.getValue().getBase(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerInstructionService");
            e.getValue().getByPosition().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(p -> add(leaves, "match.instruction-weights.bonuses." + e.getKey() + ".by-position." + p.getKey(), CanonicalScoringWeightKey.Category.INSTRUCTION, p.getValue(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerInstructionService"));
        });
        add(leaves, "match.instruction-weights.conflicts", CanonicalScoringWeightKey.Category.INSTRUCTION, match.getInstructionWeights().getConflicts().toString(), CanonicalScoringWeightKey.Type.DISCRETE, "PlayerInstructionService");
        return new CanonicalScoringWeightCatalog(leaves);
    }

    private static void addWorkRule(Map<String, CanonicalScoringWeightKey> leaves, String path, CompartmentEngineConfig.WorkRule rule) {
        add(leaves, path + ".engagement", CanonicalScoringWeightKey.Category.WORK_RATE, rule.getEngagement(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator");
        add(leaves, path + ".attack-multiplier", CanonicalScoringWeightKey.Category.WORK_RATE, rule.getAttackMultiplier(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator");
        add(leaves, path + ".ignores-defensive-instructions", CanonicalScoringWeightKey.Category.WORK_RATE, rule.isIgnoresDefensiveInstructions(), CanonicalScoringWeightKey.Type.DISCRETE, "TeamCompartmentAggregator");
        add(leaves, path + ".forced-defensive-morale-delta", CanonicalScoringWeightKey.Category.WORK_RATE, rule.getForcedDefensiveMoraleDelta(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator");
    }

    private static void addNested(Map<String, CanonicalScoringWeightKey> leaves, String prefix, Map<?, ?> values,
                                  CanonicalScoringWeightKey.Category category, String consumer) {
        values.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(String::valueOf)))
                .forEach(e -> {
                    if (e.getValue() instanceof Map<?, ?> nested) addNested(leaves, prefix + "." + e.getKey(), nested, category, consumer);
                    else add(leaves, prefix + "." + e.getKey(), category, e.getValue(), e.getValue() instanceof Boolean ? CanonicalScoringWeightKey.Type.DISCRETE : CanonicalScoringWeightKey.Type.CONTINUOUS, consumer);
                });
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
