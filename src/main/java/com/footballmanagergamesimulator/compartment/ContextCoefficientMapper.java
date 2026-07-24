package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Pure deterministic mapping from canonical tactic labels to relevant attribute coefficients. */
public final class ContextCoefficientMapper {

    private static final Map<String, List<Delta>> RULES = rules();
    private final double minimum;
    private final double maximum;

    public ContextCoefficientMapper(CompartmentEngineConfig config) {
        Objects.requireNonNull(config, "config");
        this.minimum = config.getRating().getContextCoefficientMin();
        this.maximum = config.getRating().getContextCoefficientMax();
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum) {
            throw new IllegalArgumentException("invalid context coefficient bounds");
        }
    }

    public ContextCoefficientMapping map(TacticalContextInput context) {
        if (context == null) context = TacticalContextInput.neutral();
        EnumMap<PlayerAttribute, Double> requested = new EnumMap<>(PlayerAttribute.class);
        List<ContextCoefficientMapping.Contribution> contributions = new ArrayList<>();

        apply(requested, contributions, "mentality:" + key(context.mentality()));
        apply(requested, contributions, "tempo:" + key(context.tempo()));
        apply(requested, contributions, "passing:" + key(context.passingType()));
        apply(requested, contributions, "line:" + key(context.defensiveLine()));
        apply(requested, contributions, "pressing:" + key(context.pressing()));
        apply(requested, contributions, "width:" + key(context.width()));
        for (String instruction : context.playerInstructions()) {
            apply(requested, contributions, "instruction:" + key(instruction));
        }

        EnumMap<PlayerAttribute, Double> applied = new EnumMap<>(PlayerAttribute.class);
        List<ContextCoefficientMapping.Clamp> clamps = new ArrayList<>();
        for (Map.Entry<PlayerAttribute, Double> entry : requested.entrySet()) {
            PlayerAttribute attribute = entry.getKey();
            double raw = entry.getValue();
            double bounded = Math.max(minimum, Math.min(maximum, raw));
            if (bounded != 0.0) applied.put(attribute, bounded);
            if (Double.compare(raw, bounded) != 0) {
                clamps.add(new ContextCoefficientMapping.Clamp(attribute, raw, bounded));
            }
        }
        contributions.sort(java.util.Comparator.comparing(ContextCoefficientMapping.Contribution::source)
                .thenComparing(c -> c.attribute().name()).thenComparingDouble(ContextCoefficientMapping.Contribution::delta));
        return new ContextCoefficientMapping(applied, contributions, clamps);
    }

    private static void apply(EnumMap<PlayerAttribute, Double> totals,
                              List<ContextCoefficientMapping.Contribution> breakdown, String source) {
        for (Delta delta : RULES.getOrDefault(source, List.of())) {
            totals.merge(delta.attribute, delta.value, Double::sum);
            breakdown.add(new ContextCoefficientMapping.Contribution(source, delta.attribute, delta.value));
        }
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Map<String, List<Delta>> rules() {
        Map<String, List<Delta>> r = new java.util.HashMap<>();
        // Team axes: neutral labels deliberately have no rule.
        put(r, "mentality:attacking", d(PlayerAttribute.OFF_THE_BALL, .15), d(PlayerAttribute.COMPOSURE, .10));
        put(r, "mentality:very attacking", d(PlayerAttribute.OFF_THE_BALL, .30), d(PlayerAttribute.COMPOSURE, .20));
        put(r, "mentality:defensive", d(PlayerAttribute.POSITIONING, .15), d(PlayerAttribute.CONCENTRATION, .10));
        put(r, "mentality:very defensive", d(PlayerAttribute.POSITIONING, .30), d(PlayerAttribute.CONCENTRATION, .20));
        put(r, "tempo:higher", d(PlayerAttribute.DECISIONS, .15), d(PlayerAttribute.FIRST_TOUCH, .10));
        put(r, "tempo:much higher", d(PlayerAttribute.DECISIONS, .30), d(PlayerAttribute.FIRST_TOUCH, .20));
        put(r, "tempo:lower", d(PlayerAttribute.TECHNIQUE, .10), d(PlayerAttribute.VISION, .10));
        put(r, "tempo:much lower", d(PlayerAttribute.TECHNIQUE, .20), d(PlayerAttribute.VISION, .20));
        put(r, "passing:short", d(PlayerAttribute.PASSING, .15), d(PlayerAttribute.FIRST_TOUCH, .10));
        put(r, "passing:long", d(PlayerAttribute.PASSING, .10), d(PlayerAttribute.VISION, .20), d(PlayerAttribute.KICKING, .15));
        put(r, "line:high", d(PlayerAttribute.PACE, .20), d(PlayerAttribute.ANTICIPATION, .15), d(PlayerAttribute.POSITIONING, .10));
        put(r, "line:deep", d(PlayerAttribute.HEADING, .15), d(PlayerAttribute.CONCENTRATION, .15), d(PlayerAttribute.POSITIONING, .10));
        put(r, "pressing:high", d(PlayerAttribute.WORK_RATE, .20), d(PlayerAttribute.STAMINA, .20), d(PlayerAttribute.ANTICIPATION, .10));
        put(r, "pressing:low", d(PlayerAttribute.POSITIONING, .10), d(PlayerAttribute.CONCENTRATION, .10));
        put(r, "width:wide", d(PlayerAttribute.PACE, .10), d(PlayerAttribute.DRIBBLING, .10), d(PlayerAttribute.PASSING, .05));
        put(r, "width:narrow", d(PlayerAttribute.TECHNIQUE, .10), d(PlayerAttribute.FIRST_TOUCH, .10), d(PlayerAttribute.DECISIONS, .05));
        // Canonical player instructions; each touches only attributes that explain its execution.
        put(r, "instruction:mark tighter", d(PlayerAttribute.MARKING, .20), d(PlayerAttribute.CONCENTRATION, .10));
        put(r, "instruction:close down more", d(PlayerAttribute.WORK_RATE, .15), d(PlayerAttribute.STAMINA, .10), d(PlayerAttribute.ANTICIPATION, .10));
        put(r, "instruction:close down less", d(PlayerAttribute.POSITIONING, .15), d(PlayerAttribute.CONCENTRATION, .10));
        put(r, "instruction:tackle harder", d(PlayerAttribute.TACKLING, .20), d(PlayerAttribute.BRAVERY, .10));
        put(r, "instruction:stay on feet", d(PlayerAttribute.DECISIONS, .15), d(PlayerAttribute.POSITIONING, .10));
        put(r, "instruction:ease off tackles", d(PlayerAttribute.DECISIONS, .10), d(PlayerAttribute.TACKLING, .05));
        put(r, "instruction:get further forward", d(PlayerAttribute.OFF_THE_BALL, .20), d(PlayerAttribute.STAMINA, .10));
        put(r, "instruction:hold position", d(PlayerAttribute.POSITIONING, .20), d(PlayerAttribute.CONCENTRATION, .10));
        put(r, "instruction:shoot more often", d(PlayerAttribute.FINISHING, .20), d(PlayerAttribute.COMPOSURE, .10));
        put(r, "instruction:shoot less often", d(PlayerAttribute.DECISIONS, .15), d(PlayerAttribute.PASSING, .10));
        put(r, "instruction:dribble more", d(PlayerAttribute.DRIBBLING, .20), d(PlayerAttribute.ACCELERATION, .10));
        put(r, "instruction:dribble less", d(PlayerAttribute.FIRST_TOUCH, .10), d(PlayerAttribute.PASSING, .10));
        put(r, "instruction:roam from position", d(PlayerAttribute.OFF_THE_BALL, .15), d(PlayerAttribute.DECISIONS, .10));
        put(r, "instruction:sit narrower", d(PlayerAttribute.TECHNIQUE, .10), d(PlayerAttribute.FIRST_TOUCH, .10));
        put(r, "instruction:stay wider", d(PlayerAttribute.PACE, .10), d(PlayerAttribute.DRIBBLING, .10));
        put(r, "instruction:move into channels", d(PlayerAttribute.OFF_THE_BALL, .20), d(PlayerAttribute.ANTICIPATION, .10));
        put(r, "instruction:drop deeper", d(PlayerAttribute.FIRST_TOUCH, .10), d(PlayerAttribute.VISION, .15), d(PlayerAttribute.PASSING, .10));
        put(r, "instruction:pass it shorter", d(PlayerAttribute.PASSING, .15), d(PlayerAttribute.FIRST_TOUCH, .10));
        put(r, "instruction:try more direct passes", d(PlayerAttribute.PASSING, .15), d(PlayerAttribute.VISION, .20));
        put(r, "instruction:cross from byline", d(PlayerAttribute.PACE, .10), d(PlayerAttribute.DRIBBLING, .10), d(PlayerAttribute.PASSING, .15));
        put(r, "instruction:cross from deep", d(PlayerAttribute.PASSING, .20), d(PlayerAttribute.VISION, .10));
        put(r, "instruction:play through balls", d(PlayerAttribute.VISION, .20), d(PlayerAttribute.PASSING, .15), d(PlayerAttribute.DECISIONS, .10));
        return Map.copyOf(r);
    }

    private static void put(Map<String, List<Delta>> rules, String key, Delta... deltas) { rules.put(key, List.of(deltas)); }
    private static Delta d(PlayerAttribute attribute, double value) { return new Delta(attribute, value); }
    private record Delta(PlayerAttribute attribute, double value) {}
}
