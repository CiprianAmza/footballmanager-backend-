package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;

/** Written for the long calibration gate; execution is intentionally policy-gated. */
@EnabledIfSystemProperty(named = "compartment.calibration.long", matches = "true")
class CompartmentBaselineTwoHundredSeasonsIT {
    @Test void baselineAveragePointsIsBetweenFiftyEightAndSixtyTwo() {
        var scenario = CalibrationScenarioFixtures.baseline200Season();
        var config = CalibrationConfigFixture.load();
        var harness = new ScoringSensitivityHarness(config.compartment(), config.match(), new CanonicalScoreSampler());
        var catalog = CanonicalScoringWeightCatalog.from(config.compartment(), config.match());
        var leaf = catalog.require("match.role-weights.suitability-scale");
        double baselineValue = ((Number) leaf.baselineValue()).doubleValue();
        var result = harness.run(scenario, catalog,
                new CanonicalScoringWeightOverride(leaf.path(), baselineValue));
        org.assertj.core.api.Assertions.assertThat(result.baselineAveragePoints()).isBetween(58.0, 62.0);
        org.assertj.core.api.Assertions.assertThat(result.matches()).isEqualTo(7600);
        try {
            new ScoringSensitivityReportWriter().write(java.nio.file.Path.of("target", "compartment-calibration", "baseline"),
                    "baseline", java.util.List.of(result));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
