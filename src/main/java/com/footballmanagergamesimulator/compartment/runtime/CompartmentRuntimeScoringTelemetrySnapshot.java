package com.footballmanagergamesimulator.compartment.runtime;

public record CompartmentRuntimeScoringTelemetrySnapshot(long attempted, long succeeded, long failed) {
    public CompartmentRuntimeScoringTelemetrySnapshot {
        if (attempted < 0 || succeeded < 0 || failed < 0 || attempted != succeeded + failed) {
            throw new IllegalArgumentException("runtime scoring telemetry counters are inconsistent");
        }
    }
}
