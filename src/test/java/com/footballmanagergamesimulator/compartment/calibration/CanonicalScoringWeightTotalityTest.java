package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalScoringWeightTotalityTest {
    @Test
    void everyNumericLeafHasAOneSeasonActivatorAndObservableCanonicalEffect() {
        var profile = CalibrationConfigFixture.load();
        var catalog = CanonicalScoringWeightCatalog.from(profile.compartment(), profile.match());
        var harness = new ScoringSensitivityHarness(profile.compartment(), profile.match(), new CanonicalScoreSampler());

        assertThat(catalog.leafWeights()).hasSize(727);
        for (CanonicalScoringWeightKey leaf : catalog.leafWeights()) {
            var scenario = CalibrationScenarioFactory.forWeightSmoke(leaf);
            assertThat(scenario.seasons()).as("smoke duration for %s", leaf.path()).isEqualTo(1);
            ScoringSensitivityResult result;
            try {
                result = harness.run(scenario, catalog,
                        new CanonicalScoringWeightOverride(leaf.path(), CanonicalWeightPerturbation.validAlternative(leaf)));
            } catch (RuntimeException exception) {
                throw new AssertionError(leaf.path(), exception);
            }
            assertThat(result.testedFingerprint()).as(leaf.path()).isNotEqualTo(result.baselineFingerprint());
            assertThat(result.hasObservableCanonicalEffect(1.0e-12)).as("%s %s", leaf.path(), result).isTrue();
        }
    }
}
