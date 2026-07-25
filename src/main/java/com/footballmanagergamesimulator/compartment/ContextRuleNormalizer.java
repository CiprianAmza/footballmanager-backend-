package com.footballmanagergamesimulator.compartment;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonicalizes Spring-bound context-rule map keys.
 *
 * <p>Spring's relaxed binder can add {@code tempomuchhigher} next to the Java default
 * {@code tempo:much higher}. The binder-created canonical key is authoritative because it comes
 * from the external YAML profile; the colon form is only the in-code fallback. Every runtime and
 * calibration consumer must use this same effective view.
 */
public final class ContextRuleNormalizer {

    private ContextRuleNormalizer() {
    }

    public static String canonicalKey(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace(":", "")
                .replace("-", "")
                .replace("_", "");
    }

    public static Map<String, Map<PlayerAttribute, Double>> effective(
            Map<String, Map<PlayerAttribute, Double>> source) {
        Objects.requireNonNull(source, "source");
        Map<String, Candidate> selected = new TreeMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String canonical = canonicalKey(entry.getKey());
            if (canonical.isBlank()) throw new IllegalArgumentException("context rule key cannot be blank");
            Map<PlayerAttribute, Double> row = immutableRow(entry.getKey(), entry.getValue());
            boolean externallyBound = canonical.equals(entry.getKey().trim().toLowerCase(Locale.ROOT));
            Candidate candidate = new Candidate(entry.getKey(), row, externallyBound);
            Candidate previous = selected.get(canonical);
            if (previous == null || (!previous.externallyBound() && externallyBound)) {
                selected.put(canonical, candidate);
            } else if (previous.externallyBound() == externallyBound && !previous.row().equals(row)) {
                throw new IllegalArgumentException("ambiguous context rule aliases after normalization: "
                        + previous.source() + " vs " + entry.getKey());
            }
        });

        Map<String, Map<PlayerAttribute, Double>> result = new LinkedHashMap<>();
        selected.forEach((key, value) -> result.put(key, value.row()));
        return Collections.unmodifiableMap(result);
    }

    private static Map<PlayerAttribute, Double> immutableRow(String source,
                                                              Map<PlayerAttribute, Double> values) {
        if (values == null) throw new IllegalArgumentException("context rule row is null: " + source);
        EnumMap<PlayerAttribute, Double> row = new EnumMap<>(PlayerAttribute.class);
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getKey() == null || entry.getValue() == null || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("invalid context rule value: " + source);
            }
            row.put(entry.getKey(), entry.getValue());
        });
        return Collections.unmodifiableMap(row);
    }

    private record Candidate(String source, Map<PlayerAttribute, Double> row, boolean externallyBound) {
    }
}
