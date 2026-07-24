package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CanonicalRuntimeScoringServiceTest {
    @Test
    void flagOffDoesNotInvokeSupplierOrAttempt() {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        CanonicalRuntimeScoringService service = service(config);
        AtomicBoolean invoked = new AtomicBoolean();
        assertThat(service.scoreSafely(() -> {
            invoked.set(true);
            return null;
        })).isEmpty();
        assertThat(invoked).isFalse();
        assertThat(service.telemetrySnapshot()).isEqualTo(new CompartmentRuntimeScoringTelemetrySnapshot(0, 0, 0));
    }

    @Test
    void supplierAndInvalidInputFailOpenAndCountOnce() {
        CompartmentEngineConfig config = enabledConfig();
        CanonicalRuntimeScoringService service = service(config);
        assertThat(service.scoreSafely(() -> { throw new IllegalStateException("boom"); })).isEmpty();
        assertThat(service.scoreSafely(() -> null)).isEmpty();
        assertThat(service.telemetrySnapshot()).isEqualTo(new CompartmentRuntimeScoringTelemetrySnapshot(2, 0, 2));
    }

    @Test
    void successfulEvaluationUsesCanonicalPowersAndOneSample() {
        CompartmentEngineConfig config = enabledConfig();
        CanonicalRuntimeScoringService service = service(config);
        var request = CanonicalRuntimeScoringService.RuntimeScoringRequest.home(
                "fixture", 7, 2026, 3, 11, 22,
                new PersonalizedTactic(), new PersonalizedTactic(), List.of(), List.of());
        assertThat(service.scoreSafely(() -> request)).isEmpty();
        assertThat(service.telemetrySnapshot()).isEqualTo(new CompartmentRuntimeScoringTelemetrySnapshot(1, 0, 1));
    }

    @Test
    void springConstructorIsTheOnlyPublicConstructor() {
        assertThat(java.util.Arrays.stream(CanonicalRuntimeScoringService.class.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers())).count()).isEqualTo(1);
    }

    @Test
    void bothRolloutFlagsRemainOffByDefault() {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.isShadowEnabled()).isFalse();
    }

    private static CanonicalRuntimeScoringService service(CompartmentEngineConfig config) {
        return new CanonicalRuntimeScoringService(config,
                new CanonicalRuntimeInputFactory(mock(com.footballmanagergamesimulator.service.PlayerCapabilityService.class),
                        mock(com.footballmanagergamesimulator.service.PlayerRoleService.class)),
                new CanonicalScoreSampler(), new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig()),
                new CompartmentRuntimeScoringTelemetry());
    }

    private static CompartmentEngineConfig enabledConfig() {
        CompartmentEngineConfig config = new CompartmentEngineConfig();
        config.setEnabled(true);
        return config;
    }
}
