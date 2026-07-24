package com.footballmanagergamesimulator.compartment.calibration;

import java.util.Objects;

/** Stable leaf identity used by the calibration catalog and reports. */
public record CanonicalScoringWeightKey(
        String path,
        Category category,
        Object baselineValue,
        Type type,
        PerturbationMode perturbationMode,
        String consumer) {
    public enum Category { RATING, CONTEXT, ROLE_FIT, COMPARTMENT, POSITION, ROLE, DUTY, MENTALITY, WORK_RATE, EXPOSURE, PROBABILITY, PLAYER_VALUE, INSTRUCTION, TEAM_TALK, TACTICAL }
    public enum Type { CONTINUOUS, INTEGER, DISCRETE }
    public enum PerturbationMode { DIRECT, RENORMALIZED_ATTRIBUTE }

    public CanonicalScoringWeightKey {
        path = Objects.requireNonNull(path, "path");
        category = Objects.requireNonNull(category, "category");
        baselineValue = Objects.requireNonNull(baselineValue, "baselineValue");
        type = Objects.requireNonNull(type, "type");
        perturbationMode = Objects.requireNonNull(perturbationMode, "perturbationMode");
        consumer = Objects.requireNonNull(consumer, "consumer");
        if (baselineValue instanceof Number number
                && (!Double.isFinite(number.doubleValue()))) {
            throw new IllegalArgumentException("weight must be finite: " + path);
        }
    }
}
