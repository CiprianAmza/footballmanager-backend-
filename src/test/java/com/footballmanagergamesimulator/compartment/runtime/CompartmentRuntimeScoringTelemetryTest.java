package com.footballmanagergamesimulator.compartment.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CompartmentRuntimeScoringTelemetryTest {
    @Test
    void countersAreThreadSafeAndCoherent() throws Exception {
        CompartmentRuntimeScoringTelemetry telemetry = new CompartmentRuntimeScoringTelemetry();
        int workers = 8;
        int iterations = 500;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        for (int worker = 0; worker < workers; worker++) {
            executor.submit(() -> {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    telemetry.markAttempted();
                    if ((i & 1) == 0) telemetry.markSucceeded();
                    else telemetry.markFailed();
                }
                return null;
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(telemetry.snapshot()).isEqualTo(new CompartmentRuntimeScoringTelemetrySnapshot(
                workers * iterations, workers * iterations / 2, workers * iterations / 2));
    }
}
