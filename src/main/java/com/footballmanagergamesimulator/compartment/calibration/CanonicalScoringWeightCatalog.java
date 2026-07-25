package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.footballmanagergamesimulator.service.PlayerSkillsService;

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
        add(leaves, "compartment.rating.role-fit-base", CanonicalScoringWeightKey.Category.ROLE_FIT, compartment.getRating().getRoleFitBase(), CanonicalScoringWeightKey.Type.CONTINUOUS, "role fit");
        add(leaves, "compartment.rating.role-fit-range", CanonicalScoringWeightKey.Category.ROLE_FIT, compartment.getRating().getRoleFitRange(), CanonicalScoringWeightKey.Type.CONTINUOUS, "role fit");
        add(leaves, "compartment.rating.fitness-floor", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getFitnessFloor(), CanonicalScoringWeightKey.Type.CONTINUOUS, "fitness");
        add(leaves, "compartment.rating.morale-neutral", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getMoraleNeutral(), CanonicalScoringWeightKey.Type.CONTINUOUS, "morale");
        add(leaves, "compartment.rating.morale-slope", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getMoraleSlope(), CanonicalScoringWeightKey.Type.CONTINUOUS, "morale");
        add(leaves, "compartment.rating.default-position-multiplier", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getDefaultPositionMultiplier(), CanonicalScoringWeightKey.Type.CONTINUOUS, "position fallback");
        add(leaves, "compartment.rating.default-role-multiplier", CanonicalScoringWeightKey.Category.RATING, compartment.getRating().getDefaultRoleMultiplier(), CanonicalScoringWeightKey.Type.CONTINUOUS, "role fallback");
        addContextRules(leaves, compartment.getContextRules());
        compartment.getCompartments().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                e.getValue().getAttributes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(a ->
                        add(leaves, "compartment.compartments." + e.getKey().name() + ".attributes." + a.getKey().name(), CanonicalScoringWeightKey.Category.COMPARTMENT,
                                a.getValue(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator")));
        compartment.getPositionCompartmentOverrides().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                e.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(c ->
                        c.getValue().getAttributes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(a ->
                                add(leaves, "compartment.position-compartment-overrides." + e.getKey() + "." + c.getKey().name() + ".attributes." + a.getKey().name(), CanonicalScoringWeightKey.Category.POSITION,
                                        a.getValue(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator"))));
        compartment.getPositions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> addMultipliers(leaves, "compartment.positions." + e.getKey(), e.getValue(), CanonicalScoringWeightKey.Category.POSITION));
        compartment.getRoles().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (e.getKey() != com.footballmanagergamesimulator.compartment.PlayerRole.SHADOW_STRIKER) {
                addMultipliers(leaves, "compartment.roles." + e.getKey().name(), e.getValue(), CanonicalScoringWeightKey.Category.ROLE);
            }
        });
        compartment.getDuties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> addMultipliers(leaves, "compartment.duties." + e.getKey().name(), e.getValue(), CanonicalScoringWeightKey.Category.DUTY));
        compartment.getMentalities().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            add(leaves, "compartment.mentalities." + e.getKey().name() + ".midfield-to-attack", CanonicalScoringWeightKey.Category.MENTALITY, e.getValue().getMidfieldToAttack(), CanonicalScoringWeightKey.Type.CONTINUOUS, "MentalityRule");
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
        add(leaves, "compartment.aggregation.wide-redistribution-share", CanonicalScoringWeightKey.Category.EXPOSURE, compartment.getAggregation().getWideRedistributionShare(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator");
        // The canonical compartment path only consumes the resolver's familiarity matrix.
        // PlayerValue scale/rating/morale/fitness and attribute weights belong to legacy.
        addNested(leaves, "match.player-value.familiarity-penalty", match.getPlayerValue().getFamiliarityPenalty(), CanonicalScoringWeightKey.Category.PLAYER_VALUE, "PlayerValue");
        add(leaves, "match.role-weights.suitability-scale", CanonicalScoringWeightKey.Category.ROLE_FIT, match.getRoleWeights().getSuitabilityScale(), CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerRoleService");
        addRoleAttributeWeights(leaves, match.getRoleWeights().getAttributes());
        return new CanonicalScoringWeightCatalog(leaves);
    }

    private static void addWorkRule(Map<String, CanonicalScoringWeightKey> leaves, String path, CompartmentEngineConfig.WorkRule rule) {
        if (!path.endsWith("STAY_FORWARD")) {
            add(leaves, path + ".engagement", CanonicalScoringWeightKey.Category.WORK_RATE, rule.getEngagement(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator");
        }
        add(leaves, path + ".attack-multiplier", CanonicalScoringWeightKey.Category.WORK_RATE, rule.getAttackMultiplier(), CanonicalScoringWeightKey.Type.CONTINUOUS, "TeamCompartmentAggregator");
    }

    private static void addNested(Map<String, CanonicalScoringWeightKey> leaves, String prefix, Map<?, ?> values,
                                  CanonicalScoringWeightKey.Category category, String consumer) {
        values.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(String::valueOf)))
                .forEach(e -> {
                    if (e.getValue() instanceof Map<?, ?> nested) addNested(leaves, prefix + "." + e.getKey(), nested, category, consumer);
                    else add(leaves, prefix + "." + e.getKey(), category, e.getValue(), e.getValue() instanceof Boolean ? CanonicalScoringWeightKey.Type.DISCRETE : CanonicalScoringWeightKey.Type.CONTINUOUS, consumer);
                });
    }

    private static void addRoleAttributeWeights(Map<String, CanonicalScoringWeightKey> leaves,
                                                Map<String, Map<String, Double>> values) {
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(roleEntry -> {
            PlayerRole role = resolveRole(roleEntry.getKey());
            roleEntry.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(attribute -> {
                String displayAttribute = resolveAttribute(attribute.getKey());
                add(leaves, "match.role-weights.attributes." + role.displayName() + "." + displayAttribute,
                        CanonicalScoringWeightKey.Category.ROLE_FIT, attribute.getValue(),
                        CanonicalScoringWeightKey.Type.CONTINUOUS, "PlayerRoleService");
            });
        });
    }

    private static PlayerRole resolveRole(String value) {
        return PlayerRole.fromDisplayName(value).orElseGet(() -> Arrays.stream(PlayerRole.values())
                .filter(role -> normalize(value).equals(normalize(role.displayName())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown role attribute role: " + value)));
    }

    private static String resolveAttribute(String value) {
        return PlayerSkillsService.GETTER_MAP.keySet().stream()
                .filter(attribute -> normalize(attribute).equals(normalize(value)))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown role attribute: " + value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace(" ", "").replace("-", "").replace("_", "").toUpperCase();
    }

    private static void addContextRules(Map<String, CanonicalScoringWeightKey> leaves,
                                         Map<String, Map<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double>> rules) {
        Map<String, Map.Entry<String, Map<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double>>> canonical = new java.util.TreeMap<>();
        for (Map.Entry<String, Map<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double>> entry
                : rules.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String normalized = normalizeContextSource(entry.getKey());
            var previous = canonical.get(normalized);
            if (previous == null) {
                canonical.put(normalized, entry);
                continue;
            }
            if (!isDeclaredContextAlias(previous.getKey(), entry.getKey())
                    || !previous.getValue().equals(entry.getValue())) {
                throw new IllegalArgumentException("context rule path collision after normalization: "
                        + previous.getKey() + " vs " + entry.getKey());
            }
            if (entry.getKey().contains(":") && !previous.getKey().contains(":")) canonical.put(normalized, entry);
        }
        canonical.values().forEach(entry -> entry.getValue().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> add(leaves, "compartment.context-rules." + normalizeContextSource(entry.getKey()) + "." + e.getKey().name(),
                        CanonicalScoringWeightKey.Category.CONTEXT, e.getValue(), CanonicalScoringWeightKey.Type.CONTINUOUS,
                        "ContextCoefficientMapper")));
    }

    private static boolean isDeclaredContextAlias(String first, String second) {
        return first.contains(":") != second.contains(":");
    }

    private static String normalizeContextSource(String value) {
        return value == null ? "" : value.replace(" ", "").replace(":", "");
    }

    private static void addMultipliers(Map<String, CanonicalScoringWeightKey> leaves, String path, CompartmentEngineConfig.CompartmentMultipliers m, CanonicalScoringWeightKey.Category category) {
        add(leaves, path + ".attack", category, m.getAttack(), CanonicalScoringWeightKey.Type.CONTINUOUS, "compartment multipliers");
        add(leaves, path + ".midfield", category, m.getMidfield(), CanonicalScoringWeightKey.Type.CONTINUOUS, "compartment multipliers");
        add(leaves, path + ".defense", category, m.getDefense(), CanonicalScoringWeightKey.Type.CONTINUOUS, "compartment multipliers");
    }
    private static void add(Map<String, CanonicalScoringWeightKey> leaves, String path, CanonicalScoringWeightKey.Category category, Object value, CanonicalScoringWeightKey.Type type, String consumer) {
        if (leaves.containsKey(path)) throw new IllegalArgumentException("duplicate canonical weight path: " + path);
        leaves.put(path, new CanonicalScoringWeightKey(path, category, value, type,
                CanonicalScoringWeightKey.PerturbationMode.DIRECT, consumer));
    }
    public List<CanonicalScoringWeightKey> leafWeights() {
        return byPath.values().stream()
                .filter(key -> key.type() != CanonicalScoringWeightKey.Type.DISCRETE)
                .toList();
    }
    public List<CanonicalScoringWeightKey> numericScoringWeights() { return leafWeights(); }
    public List<String> nonNumericControls() {
        return byPath.values().stream().filter(key -> key.type() == CanonicalScoringWeightKey.Type.DISCRETE)
                .map(CanonicalScoringWeightKey::path).toList();
    }
    public List<String> diagnosticOnlyParameters() { return List.of("compartment.probability.extra-time-scale", "compartment.probability.interval-lower-quantile", "compartment.probability.interval-upper-quantile"); }
    public List<String> inactiveOrFutureParameters() { return List.of("compartment.work-rate.*.forced-defensive-morale-delta", "match.instruction-weights", "match.player-value.weights"); }
    public CanonicalScoringWeightKey get(String path) { return byPath.get(path); }
    public CanonicalScoringWeightKey require(String path) { if (!byPath.containsKey(path)) throw new IllegalArgumentException("unknown weight: " + path); return byPath.get(path); }
    public int size() { return leafWeights().size(); }
}
