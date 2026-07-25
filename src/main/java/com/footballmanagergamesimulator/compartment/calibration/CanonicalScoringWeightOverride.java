package com.footballmanagergamesimulator.compartment.calibration;

import java.util.Objects;

public record CanonicalScoringWeightOverride(String key, double value) {
    public CanonicalScoringWeightOverride {
        Objects.requireNonNull(key, "key");
        if (!Double.isFinite(value)) throw new IllegalArgumentException("override must be finite");
    }
}
