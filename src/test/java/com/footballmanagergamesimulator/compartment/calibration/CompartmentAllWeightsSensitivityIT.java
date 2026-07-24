package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import java.util.List;

@EnabledIfSystemProperty(named = "compartment.calibration.long", matches = "true")
class CompartmentAllWeightsSensitivityIT {
    @Test void everyScoringWeightIsExercisedOrFailsExplicitly() {
        var c = CalibrationConfigFixture.load(); var m = new MatchEngineConfig();
        var catalog = CanonicalScoringWeightCatalog.from(c, m);
        var harness = new ScoringSensitivityHarness(c, m, new CanonicalScoreSampler());
        var scenario = CalibrationScenarioFixtures.allWeights();
        for (var leaf : catalog.leafWeights()) {
            if (leaf.type() == CanonicalScoringWeightKey.Type.DISCRETE) continue;
            double baseline = ((Number) leaf.baselineValue()).doubleValue();
            double tested = leaf.type() == CanonicalScoringWeightKey.Type.INTEGER ? baseline + 1 : baseline * 1.10;
            var result = harness.run(scenario, catalog, new CanonicalScoringWeightOverride(leaf.path(), tested));
            org.assertj.core.api.Assertions.assertThat(result.baselineFingerprint())
                    .as("weight %s", leaf.path()).isNotEqualTo(result.testedFingerprint());
            org.assertj.core.api.Assertions.assertThat(result.sampleCount()).isEqualTo(7600);
        }
    }
}
