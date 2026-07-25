package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
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

        assertThat(homeFirst.baselineXgFor() / 38.0).isCloseTo(oracleXg(fixture.baselineTeam(), fixture.opponent(), config),
                org.assertj.core.data.Offset.offset(1.0e-12));
        assertThat(homeFirst.baselineXgAgainst() / 38.0).isCloseTo(oracleXgAgainst(
                fixture.baselineTeam(), fixture.opponent(), config), org.assertj.core.data.Offset.offset(1.0e-12));
        assertThat(awayFirst.baselineXgFor() / 38.0).isCloseTo(oracleXg(fixture.opponent(), fixture.baselineTeam(), config),
                org.assertj.core.data.Offset.offset(1.0e-12));
        assertThat(awayFirst.baselineXgAgainst() / 38.0).isCloseTo(oracleXgAgainst(
                fixture.opponent(), fixture.baselineTeam(), config), org.assertj.core.data.Offset.offset(1.0e-12));
        assertThat(homeFirst.baselineXgFor()).isNotEqualTo(homeFirst.baselineXgAgainst());
        assertThat(awayFirst.baselineXgFor()).isNotEqualTo(awayFirst.baselineXgAgainst());
    }

    private static double oracleXg(CalibrationTeam analyzed, CalibrationTeam opponent,
                                  CalibrationConfigProfile config) {
        var weights = CanonicalScoringWeightSet.baseline(config.compartment(), config.match());
        var factory = new CalibrationInputFactory();
        var analyzedInput = factory.build(analyzed, weights);
        var opponentInput = factory.build(opponent, weights);
        var adapter = new CanonicalMatchEvaluationAdapter(weights.compartment(), weights.match());
        double total = 0.0;
        for (int match = 0; match < 38; match++) {
            boolean analyzedHome = match < 19;
            CanonicalMatchEvaluation evaluation = analyzedHome
                    ? adapter.evaluate(analyzedInput, opponentInput, MatchVenue.HOME)
                    : adapter.evaluate(opponentInput, analyzedInput, MatchVenue.HOME);
            total += analyzedHome ? evaluation.probability().homeXg() : evaluation.probability().awayXg();
        }
        return total / 38.0;
    }

    private static double oracleXgAgainst(CalibrationTeam analyzed, CalibrationTeam opponent,
                                          CalibrationConfigProfile config) {
        var weights = CanonicalScoringWeightSet.baseline(config.compartment(), config.match());
        var factory = new CalibrationInputFactory();
        var analyzedInput = factory.build(analyzed, weights);
        var opponentInput = factory.build(opponent, weights);
        var adapter = new CanonicalMatchEvaluationAdapter(weights.compartment(), weights.match());
        double total = 0.0;
        for (int match = 0; match < 38; match++) {
            boolean analyzedHome = match < 19;
            CanonicalMatchEvaluation evaluation = analyzedHome
                    ? adapter.evaluate(analyzedInput, opponentInput, MatchVenue.HOME)
                    : adapter.evaluate(opponentInput, analyzedInput, MatchVenue.HOME);
            total += analyzedHome ? evaluation.probability().awayXg() : evaluation.probability().homeXg();
        }
        return total / 38.0;
    }
}
