package com.footballmanagergamesimulator.compartment.runtime;

import org.springframework.stereotype.Component;

@Component
public final class CompartmentRuntimeScoringTelemetry {
    private long attempted;
    private long succeeded;
    private long failed;

    public synchronized void markSucceeded() {
        attempted++;
        succeeded++;
    }

    public synchronized void markFailed() {
        attempted++;
        failed++;
    }

    public synchronized CompartmentRuntimeScoringTelemetrySnapshot snapshot() {
        return new CompartmentRuntimeScoringTelemetrySnapshot(attempted, succeeded, failed);
    }
}
