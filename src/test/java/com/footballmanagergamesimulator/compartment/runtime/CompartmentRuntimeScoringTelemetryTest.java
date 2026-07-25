package com.footballmanagergamesimulator.compartment.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CompartmentRuntimeScoringTelemetryTest {
    @Test
    void countersAreThreadSafeAndCoherentWhileSnapshotsAreRead() throws Exception {
        CompartmentRuntimeScoringTelemetry telemetry = new CompartmentRuntimeScoringTelemetry();
        int workers = 8;
        int iterations = 500;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean invalidSnapshot = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        for (int worker = 0; worker < workers; worker++) {
            executor.submit(() -> {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    if ((i & 1) == 0) telemetry.markSucceeded();
                    else telemetry.markFailed();
                }
                return null;
            });
        }
        Thread reader = new Thread(() -> {
            while (!finished.get()) {
                try {
                    CompartmentRuntimeScoringTelemetrySnapshot snapshot = telemetry.snapshot();
                    if (snapshot.attempted() != snapshot.succeeded() + snapshot.failed()) {
                        invalidSnapshot.set(true);
                    }
                } catch (RuntimeException ex) {
                    invalidSnapshot.set(true);
                }
            }
        });
        reader.start();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        finished.set(true);
        reader.join(2_000);
        assertThat(invalidSnapshot).isFalse();
        assertThat(telemetry.snapshot()).isEqualTo(new CompartmentRuntimeScoringTelemetrySnapshot(
                workers * iterations, workers * iterations / 2, workers * iterations / 2));
    }
}
