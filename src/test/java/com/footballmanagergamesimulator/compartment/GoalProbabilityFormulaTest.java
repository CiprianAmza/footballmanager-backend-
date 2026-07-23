package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GoalProbabilityFormulaTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();
    private final GoalProbabilityFormula formula = new GoalProbabilityFormula(config);

    @Test
    void equalMatchupUsesConfiguredHomeAdvantageAndSymmetricShares() {
        var result = formula.expectedGoals(100, 100, 100, 100, 3.0);
        assertThat(result.homeMatchupShare()).isEqualTo(0.5);
        assertThat(result.awayMatchupShare()).isEqualTo(0.5);
        assertThat(result.homeXg()).isCloseTo(1.62, within(1e-12));
        assertThat(result.awayXg()).isCloseTo(1.50, within(1e-12));
    }

    @Test
    void thirtyPercentContextualAdvantageRaisesShareButLeavesUpsetMass() {
        double favoriteShare = GoalProbabilityFormula.matchupShare(130, 100, 1.5);
        double outsiderShare = GoalProbabilityFormula.matchupShare(100, 130, 1.5);
        assertThat(favoriteShare).isGreaterThan(0.5).isLessThan(1.0);
        assertThat(outsiderShare).isGreaterThan(0.0).isLessThan(0.5);
        assertThat(favoriteShare + outsiderShare).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void gammaPoissonPredictiveDistributionIsExactCappedAndOverdispersed() {
        var distribution = formula.predictiveGoals(1.5);
        assertThat(distribution.probabilities()).hasSize(8);
        assertThat(Arrays.stream(distribution.probabilities()).sum()).isCloseTo(1.0, within(1e-12));
        assertThat(distribution.probabilities()[0]).isGreaterThan(Math.exp(-1.5));
        assertThat(distribution.p05()).isBetween(0, 7);
        assertThat(distribution.p95()).isBetween(distribution.p05(), 7);

        double[] leaked = distribution.probabilities();
        leaked[0] = -1;
        assertThat(distribution.probabilities()[0]).isGreaterThan(0);
    }
}
