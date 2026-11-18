package com.footballmanagergamesimulator.compartment.match;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ExpectedPointsTest {

    @Test
    void convolutionOfTwoKnownDistributions() {
        // 0/1 goal with 50% each, twice -> 0,1,2 with 25/50/25.
        double[] sum = ExpectedPoints.convolve(new double[]{0.5, 0.5}, new double[]{0.5, 0.5});
        assertThat(sum).containsExactly(new double[]{0.25, 0.5, 0.25}, within(1e-15));
        assertThat(ExpectedPoints.mean(sum)).isCloseTo(1.0, within(1e-15));
    }

    @Test
    void binomialThinningAndCompoundKeepMassAndMean() {
        double[] counts = {0.1, 0.2, 0.3, 0.4};
        double[] thinned = ExpectedPoints.thin(counts, 0.75);
        assertThat(Arrays.stream(thinned).sum()).isCloseTo(1.0, within(1e-12));
        assertThat(ExpectedPoints.mean(thinned)).isCloseTo(0.75 * ExpectedPoints.mean(counts), within(1e-12));
        assertThat(ExpectedPoints.binomial(3, 0.5)).containsExactly(new double[]{0.125, 0.375, 0.375, 0.125}, within(1e-15));

        // SHOOTER-like: shots 0..5 with the standard distribution, each scoring with 30%.
        List<Double> shots = List.of(0.25, 0.40, 0.20, 0.075, 0.05, 0.025);
        double[] goals = ExpectedPoints.compound(shots, 0.30);
        double meanShots = 0.40 + 2 * 0.20 + 3 * 0.075 + 4 * 0.05 + 5 * 0.025;
        assertThat(Arrays.stream(goals).sum()).isCloseTo(1.0, within(1e-12));
        assertThat(ExpectedPoints.mean(goals)).isCloseTo(0.30 * meanShots, within(1e-12));
    }

    @Test
    void outcomeOfTwoDistributionsIsExactAndBounded() {
        double[] home = {0.2, 0.5, 0.3};
        double[] away = {0.6, 0.4};
        ExpectedPoints.Outcome outcome = ExpectedPoints.fromDistributions(home, away);
        // home win: 1-0 .5*.6 + 2-0 .3*.6 + 2-1 .3*.4 = .30 + .18 + .12
        assertThat(outcome.homeWin()).isCloseTo(0.60, within(1e-12));
        assertThat(outcome.draw()).isCloseTo(0.2 * 0.6 + 0.5 * 0.4, within(1e-12));
        assertThat(outcome.awayWin()).isCloseTo(0.2 * 0.4, within(1e-12));
        assertThat(outcome.homeWin() + outcome.draw() + outcome.awayWin()).isCloseTo(1.0, within(1e-12));
        assertThat(outcome.homeXpts()).isBetween(0.0, 3.0);
        assertThat(outcome.awayXpts()).isBetween(0.0, 3.0);
        assertThat(outcome.homeXpts() + outcome.awayXpts()).isCloseTo(3.0 - outcome.draw(), within(1e-12));

        ExpectedPoints.Outcome swapped = ExpectedPoints.fromDistributions(away, home);
        assertThat(swapped.homeXpts()).isCloseTo(outcome.awayXpts(), within(1e-12));
        assertThat(swapped.awayXpts()).isCloseTo(outcome.homeXpts(), within(1e-12));
    }

    @Test
    void poissonFallbackIsSymmetricBoundedAndMonotone() {
        ExpectedPoints.Outcome equal = ExpectedPoints.poisson(1.4, 1.4);
        assertThat(equal.homeXpts()).isCloseTo(equal.awayXpts(), within(1e-12));
        ExpectedPoints.Outcome zero = ExpectedPoints.poisson(0.0, 0.0);
        assertThat(zero.homeXpts()).isCloseTo(1.0, within(1e-12));
        assertThat(zero.draw()).isCloseTo(1.0, within(1e-12));

        ExpectedPoints.Outcome strong = ExpectedPoints.poisson(2.5, 0.5);
        assertThat(strong.homeXpts()).isBetween(2.0, 3.0);
        assertThat(strong.awayXpts()).isBetween(0.0, 1.0);
        assertThat(ExpectedPoints.poisson(3.0, 0.5).homeXpts()).isGreaterThan(strong.homeXpts());

        // Goals 0..10 with the tail folded into 10: the PMF always sums to 1.
        assertThat(Arrays.stream(ExpectedPoints.poissonPmf(9.0)).sum()).isCloseTo(1.0, within(1e-12));
        assertThat(ExpectedPoints.poissonPmf(9.0)).hasSize(ExpectedPoints.MAX_POISSON_GOALS + 1);
    }
}
