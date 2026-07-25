package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.match.OutcomeProbability;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalScoreSamplerTest {
    @Test
    void sameSeedIsDeterministicAndDifferentSeedsRemainBounded() {
        CanonicalScoreSampler sampler = new CanonicalScoreSampler();
        CanonicalScoreSampler.GoalSample first = sampler.sample(evaluation(new double[]{.2, .3, .5},
                new double[]{.1, .9}), 42L);
        assertThat(sampler.sample(evaluation(new double[]{.2, .3, .5}, new double[]{.1, .9}), 42L))
                .isEqualTo(first);
        for (long seed = 0; seed < 100; seed++) {
            CanonicalScoreSampler.GoalSample sample = sampler.sample(
                    evaluation(new double[]{.2, .3, .5}, new double[]{.1, .9}), seed);
            assertThat(sample.homeGoals()).isBetween(0, 2);
            assertThat(sample.awayGoals()).isBetween(0, 1);
        }
    }

    @Test
    void inverseCdfUsesFirstIntermediateAndLastBuckets() {
        CanonicalScoreSampler sampler = new CanonicalScoreSampler();
        CanonicalScoreSampler.GoalSample first = sampler.sample(evaluation(new double[]{1.0, 0.0, 0.0},
                new double[]{1.0}), 0L);
        assertThat(first.homeGoals()).isZero();

        boolean sawIntermediate = false;
        boolean sawLast = false;
        for (long seed = 0; seed < 10_000 && (!sawIntermediate || !sawLast); seed++) {
            CanonicalScoreSampler.GoalSample sample = sampler.sample(
                    evaluation(new double[]{.2, .5, .3}, new double[]{1.0}), seed);
            sawIntermediate |= sample.homeGoals() == 1;
            sawLast |= sample.homeGoals() == 2;
        }
        assertThat(sawIntermediate).isTrue();
        assertThat(sawLast).isTrue();
    }

    @Test
    void invalidPmfIsRejected() {
        assertThatThrownBy(() -> new CanonicalScoreSampler().sample(
                evaluation(new double[]{.4, .4}, new double[]{1.0}), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalScoreSampler().sample(
                evaluation(new double[]{Double.NaN}, new double[]{1.0}), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void homeAndAwayUseSuccessiveIndependentDraws() {
        CanonicalScoreSampler.GoalSample sample = new CanonicalScoreSampler().sample(
                evaluation(new double[]{.5, .5}, new double[]{.5, .5}), 123L);
        assertThat(sample.homeGoals()).isBetween(0, 1);
        assertThat(sample.awayGoals()).isBetween(0, 1);
    }

    private static CanonicalMatchEvaluation evaluation(double[] homePmf, double[] awayPmf) {
        GoalProbabilityFormula.GoalDistribution home = distribution(homePmf);
        GoalProbabilityFormula.GoalDistribution away = distribution(awayPmf);
        GoalProbabilityFormula.MatchProbability probability = new GoalProbabilityFormula.MatchProbability(
                .5, .5, 1.0, 1.0, home, away);
        TeamCompartmentAggregator.TeamAggregationResult homeTeam = aggregation(10, 5);
        TeamCompartmentAggregator.TeamAggregationResult awayTeam = aggregation(11, 6);
        return new CanonicalMatchEvaluation(new CanonicalTeamEvaluation(java.util.List.of(), homeTeam),
                new CanonicalTeamEvaluation(java.util.List.of(), awayTeam), MatchVenue.HOME, 1.0,
                probability, new OutcomeProbability(.3, .4, .3));
    }

    private static TeamCompartmentAggregator.TeamAggregationResult aggregation(double attack, double protection) {
        return new TeamCompartmentAggregator.TeamAggregationResult(Mentality.BALANCED, 1.0,
                new TeamCompartmentAggregator.RawTotals(0, 0, 0),
                new TeamCompartmentAggregator.MentalityRedistribution(0, 0, null, null, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                java.util.Map.of(), null,
                new TeamCompartmentAggregator.ExposureBreakdown(0, 0, 0, 1, protection, protection),
                java.util.List.of(), attack, protection);
    }

    private static GoalProbabilityFormula.GoalDistribution distribution(double[] pmf) {
        return new GoalProbabilityFormula.GoalDistribution(1.0, 1.0, pmf.length - 1, pmf, 0, pmf.length - 1);
    }
}
