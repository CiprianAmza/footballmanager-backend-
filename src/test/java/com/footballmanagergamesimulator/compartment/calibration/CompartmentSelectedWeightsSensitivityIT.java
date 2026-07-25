package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import java.util.List;

@EnabledIfSystemProperty(named = "compartment.calibration.long", matches = "true")
class CompartmentSelectedWeightsSensitivityIT {
    @Test void paceShadowStrikerMoraleStayForwardAndDefensiveMentalityAreReported() throws Exception {
        var config = CalibrationConfigFixture.load();
        var c = config.compartment(); var m = config.match();
        var harness = new ScoringSensitivityHarness(c, m, new CanonicalScoreSampler());
        var catalog = CanonicalScoringWeightCatalog.from(c, m);
        var results = new java.util.ArrayList<ScoringSensitivityResult>();
        var base = CalibrationScenarioFixtures.selectedWeights();
        List<Experiment> experiments = List.of(
                new Experiment("pace-high-line", base.baselineTeam().withDefensiveLine("High"), "compartment.context-rules.linehigh.PACE"),
                new Experiment("morale-non-neutral", base.baselineTeam().withMorale(85), "compartment.rating.morale-slope"),
                new Experiment("stay-forward", base.baselineTeam().withStayForward(), "compartment.work-rate.instructions.STAY_FORWARD.attack-multiplier"),
                new Experiment("defensive-mentality", base.baselineTeam().withMentality(com.footballmanagergamesimulator.compartment.Mentality.DEFENSIVE), "compartment.mentalities.DEFENSIVE.openness"));
        for (Experiment experiment : experiments) {
            var scenario = new ScoringSensitivityScenario(experiment.id(), experiment.team(), base.opponent(), base.seed(), 200);
            if (experiment.id().equals("stay-forward")) {
                var active = scenario.baselineTeam().players().stream()
                        .filter(player -> player.instruction() == ForwardInstruction.STAY_FORWARD)
                        .findFirst().orElseThrow();
                org.assertj.core.api.Assertions.assertThat(active.position()).isEqualTo(PlayerPosition.ST);
                org.assertj.core.api.Assertions.assertThat(active.role()).isEqualTo(PlayerRole.POACHER);
            }
            var leaf = catalog.require(experiment.key()); double value = CanonicalWeightPerturbation.validAlternative(leaf);
            var result = harness.run(scenario, catalog, new CanonicalScoringWeightOverride(experiment.key(), value));
            results.add(result);
            org.assertj.core.api.Assertions.assertThat(result.baselineFingerprint()).isNotEqualTo(result.testedFingerprint());
            org.assertj.core.api.Assertions.assertThat(result.confidenceInterval()).isGreaterThanOrEqualTo(0.0);
        }
        new ScoringSensitivityReportWriter().write(java.nio.file.Path.of("target", "compartment-calibration", "selected"),
                "sensitivity", results);
    }

    private record Experiment(String id, CalibrationTeam team, String key) {}
}
