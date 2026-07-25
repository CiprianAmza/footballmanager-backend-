package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringSensitivityHarnessTest {
    @Test
    void miniRunRebuildsBothPipelinesWithCommonSeedsAndPairedDelta() {
        var fixture = CalibrationScenarioFixtures.selectedWeights();
        var scenario = new ScoringSensitivityScenario("mini", fixture.baselineTeam(), fixture.opponent(), fixture.seed(), 2);
        var config = CalibrationConfigFixture.load();
        var compartment = config.compartment();
        var match = config.match();
        var catalog = CanonicalScoringWeightCatalog.from(compartment, match);
        var result = new ScoringSensitivityHarness(compartment, match, new CanonicalScoreSampler()).run(scenario,
                catalog, new CanonicalScoringWeightOverride("match.role-weights.suitability-scale", 5.5));
        assertThat(result.matches()).isEqualTo(76);
        assertThat(result.sampleCount()).isEqualTo(76);
        assertThat(result.pairedSeasonDeltas()).hasSize(2);
        assertThat(result.baselineFingerprint()).isNotEqualTo(result.testedFingerprint());
        assertThat(result.pointsDelta()).isEqualTo(result.testedAveragePoints() - result.baselineAveragePoints());
    }

    @Test
    void asymmetricTeamUsesHomeAndAwayXgForBothOrientations() {
        var fixture = CalibrationScenarioFixtures.selectedWeights();
        var config = CalibrationConfigFixture.load();
        var catalog = CanonicalScoringWeightCatalog.from(config.compartment(), config.match());
        var harness = new ScoringSensitivityHarness(config.compartment(), config.match(), new CanonicalScoreSampler());
        var homeFirst = harness.run(
                new ScoringSensitivityScenario("xg-home-first", fixture.baselineTeam(), fixture.opponent(),
                        fixture.seed(), 1), catalog,
                new CanonicalScoringWeightOverride("match.role-weights.suitability-scale", 5.5));
        var awayFirst = harness.run(
                new ScoringSensitivityScenario("xg-away-first", fixture.opponent(), fixture.baselineTeam(),
                        fixture.seed(), 1), catalog,
                new CanonicalScoringWeightOverride("match.role-weights.suitability-scale", 5.5));

        assertThat(homeFirst.baselineXgFor()).isNotEqualTo(homeFirst.baselineXgAgainst());
        assertThat(awayFirst.baselineXgFor()).isNotEqualTo(awayFirst.baselineXgAgainst());
    }
}
