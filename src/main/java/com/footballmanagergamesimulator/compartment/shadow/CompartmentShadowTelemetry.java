package com.footballmanagergamesimulator.compartment.shadow;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

@Component
public final class CompartmentShadowTelemetry {
    private final LongAdder attempted = new LongAdder();
    private final LongAdder succeeded = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final Map<CompartmentShadowSkipReason, LongAdder> skippedByReason = new EnumMap<>(CompartmentShadowSkipReason.class);

    public CompartmentShadowTelemetry() {
        for (CompartmentShadowSkipReason reason : CompartmentShadowSkipReason.values()) {
            skippedByReason.put(reason, new LongAdder());
        }
    }

    public void markAttempted() { attempted.increment(); }
    public void markSucceeded() { succeeded.increment(); }
    public void markFailed() { failed.increment(); }

    public void markSkipped(CompartmentShadowSkipReason reason) {
        skipped.increment();
        skippedByReason.get(reason).increment();
    }

    public CompartmentShadowTelemetrySnapshot snapshot() {
        EnumMap<CompartmentShadowSkipReason, Long> byReason = new EnumMap<>(CompartmentShadowSkipReason.class);
        skippedByReason.forEach((reason, count) -> byReason.put(reason, count.sum()));
        return new CompartmentShadowTelemetrySnapshot(
                attempted.sum(), succeeded.sum(), failed.sum(), skipped.sum(), byReason);
    }
}
