package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import java.util.List;

@EnabledIfSystemProperty(named = "compartment.calibration.long", matches = "true")
class CompartmentSelectedWeightsSensitivityIT {
    @Test void paceShadowStrikerMoraleStayForwardAndDefensiveMentalityAreReported() {
        var scenario = CalibrationScenarioFixtures.selectedWeights();
        var c = CalibrationConfigFixture.load(); var m = new MatchEngineConfig();
        var harness = new ScoringSensitivityHarness(c, m, new CanonicalScoreSampler());
        var catalog = CanonicalScoringWeightCatalog.from(c, m);
        for (String key : List.of("compartment.context-rules.line:high.PACE", "compartment.roles.POACHER.attack",
                "match.player-value.morale-slope", "compartment.exposure.coverage-reduction",
                "compartment.mentalities.DEFENSIVE.openness")) {
            var leaf = catalog.require(key); double value = ((Number) leaf.baselineValue()).doubleValue() * 1.10;
            var result = harness.run(scenario, catalog, new CanonicalScoringWeightOverride(key, value));
            org.assertj.core.api.Assertions.assertThat(result.baselineFingerprint()).isNotEqualTo(result.testedFingerprint());
            org.assertj.core.api.Assertions.assertThat(result.confidenceInterval()).isGreaterThanOrEqualTo(0.0);
        }
    }
}
