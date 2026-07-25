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

    private final Map<String, List<Delta>> rules;
    private final double minimum;
    private final double maximum;

    public ContextCoefficientMapper(CompartmentEngineConfig config) {
        Objects.requireNonNull(config, "config");
        this.minimum = config.getRating().getContextCoefficientMin();
        this.maximum = config.getRating().getContextCoefficientMax();
        this.rules = copyRules(config.getContextRules());
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

    private void apply(EnumMap<PlayerAttribute, Double> totals,
                              List<ContextCoefficientMapping.Contribution> breakdown, String source) {
        for (Delta delta : rules.getOrDefault(source, List.of())) {
            totals.merge(delta.attribute, delta.value, Double::sum);
            breakdown.add(new ContextCoefficientMapping.Contribution(source, delta.attribute, delta.value));
        }
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Map<String, List<Delta>> copyRules(Map<String, Map<PlayerAttribute, Double>> source) {
        Map<String, List<Delta>> copy = new java.util.TreeMap<>();
        source.forEach((key, row) -> copy.put(key, row.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new Delta(e.getKey(), e.getValue())).toList()));
        return java.util.Collections.unmodifiableMap(copy);
    }
    private record Delta(PlayerAttribute attribute, double value) {}
}
