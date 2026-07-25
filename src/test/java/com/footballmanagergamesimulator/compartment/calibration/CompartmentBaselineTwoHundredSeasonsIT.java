package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;

/** Written for the long calibration gate; execution is intentionally policy-gated. */
@EnabledIfSystemProperty(named = "compartment.calibration.long", matches = "true")
class CompartmentBaselineTwoHundredSeasonsIT {
    @Test void baselineClubFinishesNearTheMiddleOfADistributedLeague() {
        var config = CalibrationConfigFixture.load();
        var harness = new LeagueCalibrationHarness(config.compartment(), config.match(), new CanonicalScoreSampler());
        var result = harness.run(CalibrationScenarioFixtures.midTableLeague(), 200, 13_071_991L);
        org.assertj.core.api.Assertions.assertThat(result.averageRank()).isBetween(8.0, 13.0);
        org.assertj.core.api.Assertions.assertThat(result.rankPercentile()).isBetween(0.35, 0.65);
        org.assertj.core.api.Assertions.assertThat(result.matches()).isEqualTo(76_000);
        try {
            java.nio.file.Path directory = java.nio.file.Path.of("target", "compartment-calibration", "baseline");
            java.nio.file.Files.createDirectories(directory);
            String report = "seasons,matches,averagePoints,averageRank,rankPercentile\n"
                    + result.seasons() + ',' + result.matches() + ',' + result.averagePoints() + ','
                    + result.averageRank() + ',' + result.rankPercentile() + '\n';
            java.nio.file.Files.writeString(directory.resolve("league-baseline.csv"), report);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
