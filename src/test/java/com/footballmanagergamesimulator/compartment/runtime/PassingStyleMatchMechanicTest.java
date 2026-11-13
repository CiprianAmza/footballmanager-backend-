package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PassingMidfielder;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PassingStriker;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.PassingStyleProfile;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PassingStyleMatchMechanicTest {
    private final PassingStyleMatchMechanic mechanic =
            new PassingStyleMatchMechanic(new CompartmentEngineConfig());

    @Test
    void reproducesTheSpecifiedLongPassingAndPaceExample() {
        PassingStyleProfile profile = profile(20, 19, 19, 20, 20);

        assertThat(mechanic.suppression(profile, null, "Long", "Normal"))
                .isCloseTo(0.80, within(1e-12));
    }

    @Test
    void counterSettingsUseTheStrongestApplicableBase() {
        PassingStyleProfile profile = profile(20, 19, 19, 20, 20);

        assertThat(mechanic.suppression(profile, null, "Normal", "Normal")).isCloseTo(0.94, within(1e-12));
        assertThat(mechanic.suppression(profile, null, "Normal", "Aggressive")).isCloseTo(0.92, within(1e-12));
        assertThat(mechanic.suppression(profile, null, "Normal", "Very Aggressive")).isCloseTo(0.85, within(1e-12));
        assertThat(mechanic.suppression(profile, null, "Long", "Very Aggressive")).isCloseTo(0.80, within(1e-12));
    }

    @Test
    void sixPace19MidfieldersKeepOnlyModerateControlAndCapTheStrikerAt25Percent() {
        PassingStyleProfile profile = profile(19, 19, 19, 19, 19, 19);

        assertThat(mechanic.suppression(profile, null, "Normal", "Normal"))
                .isCloseTo(0.39, within(1e-12));
        assertThat(mechanic.suppression(profile, null, "Normal", "Aggressive"))
                .isCloseTo(0.37, within(1e-12));
        assertThat(mechanic.suppression(profile, null, "Normal", "Very Aggressive"))
                .isCloseTo(0.30, within(1e-12));
        assertThat(mechanic.suppression(profile, null, "Long", "Normal"))
                .isCloseTo(0.25, within(1e-12));
        assertThat(mechanic.strikerGoalChance(0.39, 20, 19, 0))
                .isCloseTo(0.25, within(1e-12));
    }

    @Test
    void finishing20ConvertsEightyPercentAndFinishing19Sixty() {
        assertThat(mechanic.strikerGoalChance(0.60, 20, 20, 0)).isCloseTo(0.48, within(1e-12));
        assertThat(mechanic.strikerGoalChance(0.60, 20, 19, 0)).isCloseTo(0.25, within(1e-12));
        assertThat(mechanic.strikerGoalChance(0.60, 20, 20, 0.20)).isCloseTo(0.28, within(1e-12));
        assertThat(mechanic.strikerGoalChance(0.60, 19, 20, 0)).isCloseTo(0.36, within(1e-12));
        assertThat(mechanic.strikerGoalChance(0.60, 18, 20, 0))
                .isLessThan(0.36)
                .isCloseTo(0.3410526315789474, within(1e-12));
    }

    @Test
    void everyFinishingLevelAbove20AddsFourPointsUpToTheFullControlChance() {
        assertThat(mechanic.strikerGoalChance(1.0, 21, 20, 0)).isCloseTo(0.84, within(1e-12));
        assertThat(mechanic.strikerGoalChance(1.0, 22, 20, 0)).isCloseTo(0.88, within(1e-12));
        assertThat(mechanic.strikerGoalChance(1.0, 25, 20, 0)).isCloseTo(1.00, within(1e-12));
        assertThat(mechanic.strikerGoalChance(1.0, 30, 20, 0)).isCloseTo(1.00, within(1e-12));
    }

    @Test
    void paceAbove20RaisesTheMidfieldBonusAndTheOpponentPenaltyByOnePointPerLevel() {
        // Long-passing base 0.85; each non-20 costs 10%, a 20 adds 5%, a 22 adds 7%.
        assertThat(mechanic.suppression(profile(19, 19), null, "Long", "Normal"))
                .isCloseTo(0.65, within(1e-12));
        assertThat(mechanic.suppression(profile(20, 19), null, "Long", "Normal"))
                .isCloseTo(0.80, within(1e-12));
        assertThat(mechanic.suppression(profile(22, 19), null, "Long", "Normal"))
                .isCloseTo(0.82, within(1e-12));

        assertThat(mechanic.opponentPace20Penalty(profile(19, 20, 22), null)).isCloseTo(0.10 + 0.12, within(1e-12));
        assertThat(mechanic.opponentPace20Penalty(profile(20, 22), 2L)).isCloseTo(0.10, within(1e-12));
        // Pace above 20 is still uncapped for the striker.
        assertThat(mechanic.strikerGoalChance(0.60, 19, 23, 0)).isCloseTo(0.36, within(1e-12));
    }

    @Test
    void opportunityDistributionHasTheRequestedSevenBuckets() {
        assertThat(mechanic.sampleOpportunityCount(0.00)).isZero();
        assertThat(mechanic.sampleOpportunityCount(0.149999)).isZero();
        assertThat(mechanic.sampleOpportunityCount(0.15)).isEqualTo(1);
        assertThat(mechanic.sampleOpportunityCount(0.399999)).isEqualTo(1);
        assertThat(mechanic.sampleOpportunityCount(0.999999)).isEqualTo(6);
    }

    private static PassingStyleProfile profile(int... paces) {
        java.util.ArrayList<PassingMidfielder> midfielders = new java.util.ArrayList<>();
        for (int i = 0; i < paces.length; i++) {
            midfielders.add(new PassingMidfielder(i + 1L, paces[i], 19, 19));
        }
        return new PassingStyleProfile(true, 19.0, midfielders,
                List.of(new PassingStriker(100, 20, 20)));
    }
}
