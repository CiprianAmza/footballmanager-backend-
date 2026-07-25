package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.calibration.CompartmentCalibrationAccumulator;
import com.footballmanagergamesimulator.compartment.shadow.CompartmentShadowEvaluationService;
import com.footballmanagergamesimulator.compartment.shadow.CompartmentShadowTelemetry;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.service.PlayerCapabilityService;
import com.footballmanagergamesimulator.service.PlayerRoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CanonicalSpringWiringTest.WiringConfig.class)
class CanonicalSpringWiringTest {
    @Autowired private CanonicalRuntimeScoringService scoringService;
    @Autowired private CompartmentShadowEvaluationService shadowService;

    @Test
    void componentScanningAutowiresBothCanonicalServices() {
        assertThat(scoringService).isNotNull();
        assertThat(shadowService).isNotNull();
    }

    @Configuration
    @ComponentScan(
            basePackageClasses = {CanonicalRuntimeScoringService.class, CompartmentShadowEvaluationService.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {CanonicalRuntimeScoringService.class, CompartmentShadowEvaluationService.class}))
    static class WiringConfig {
        @Bean CompartmentEngineConfig compartmentEngineConfig() { return new CompartmentEngineConfig(); }
        @Bean MatchEngineConfig matchEngineConfig() { return new MatchEngineConfig(); }
        @Bean CanonicalRuntimeInputFactory runtimeInputFactory() {
            return new CanonicalRuntimeInputFactory(
                    mock(PlayerCapabilityService.class), mock(PlayerRoleService.class));
        }
        @Bean CanonicalScoreSampler scoreSampler() { return new CanonicalScoreSampler(); }
        @Bean CompartmentRuntimeScoringTelemetry scoringTelemetry() { return new CompartmentRuntimeScoringTelemetry(); }
        @Bean CanonicalScoringFingerprintService fingerprintService() { return new CanonicalScoringFingerprintService(); }
        @Bean CompartmentShadowTelemetry shadowTelemetry() { return new CompartmentShadowTelemetry(); }
        @Bean CompartmentCalibrationAccumulator calibrationAccumulator() { return new CompartmentCalibrationAccumulator(); }
    }
}
