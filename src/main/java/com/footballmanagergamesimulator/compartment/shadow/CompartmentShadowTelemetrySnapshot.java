package com.footballmanagergamesimulator.compartment.shadow;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record CompartmentShadowTelemetrySnapshot(
        long attempted,
        long succeeded,
        long failed,
        long skipped,
        Map<CompartmentShadowSkipReason, Long> skippedByReason) {
    public CompartmentShadowTelemetrySnapshot {
        if (attempted < 0 || succeeded < 0 || failed < 0 || skipped < 0) {
            throw new IllegalArgumentException("telemetry counters must be non-negative");
        }
        EnumMap<CompartmentShadowSkipReason, Long> copy = new EnumMap<>(CompartmentShadowSkipReason.class);
        if (skippedByReason != null) copy.putAll(skippedByReason);
        skippedByReason = Collections.unmodifiableMap(copy);
    }
}
