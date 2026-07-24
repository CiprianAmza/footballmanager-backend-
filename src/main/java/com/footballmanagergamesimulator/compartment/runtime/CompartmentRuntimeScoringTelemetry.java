package com.footballmanagergamesimulator.compartment.runtime;

import org.springframework.stereotype.Component;

@Component
public final class CompartmentRuntimeScoringTelemetry {
    private long attempted;
    private long succeeded;
    private long failed;

    public synchronized void markAttempted() { attempted++; }
    public synchronized void markSucceeded() { succeeded++; }
    public synchronized void markFailed() { failed++; }

    public synchronized CompartmentRuntimeScoringTelemetrySnapshot snapshot() {
        return new CompartmentRuntimeScoringTelemetrySnapshot(attempted, succeeded, failed);
    }
}
