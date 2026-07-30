package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.match.OutcomeProbability;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
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

    @Test
    void productionSampleKeepsCollectiveAndShooterGoalsAsSeparateAdditiveComponents() {
        TeamCompartmentAggregator.TeamAggregationResult homeTeam = aggregation(
                10, 5, "Very Easy", new TeamCompartmentAggregator.ShooterProfile(99L, 20, 20));
        TeamCompartmentAggregator.TeamAggregationResult awayTeam = aggregation(10, 5, "Very Easy", null);
        CanonicalMatchEvaluation evaluation = evaluation(homeTeam, awayTeam);
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(new CompartmentEngineConfig());

        CanonicalScoreSampler.GoalSample withShooterGoal = null;
        for (long seed = 0; seed < 10_000; seed++) {
            CanonicalScoreSampler.GoalSample candidate = sampler.sample(evaluation, seed);
            assertThat(candidate.homeGoals())
                    .isEqualTo(candidate.homeCollectiveGoals() + candidate.homeShooterGoals());
            assertThat(candidate.awayGoals())
                    .isEqualTo(candidate.awayCollectiveGoals() + candidate.awayShooterGoals());
            assertThat(candidate.homeShooterShots()).isGreaterThanOrEqualTo(candidate.homeShooterGoals());
            if (candidate.homeShooterGoals() > 0) {
                withShooterGoal = candidate;
                break;
            }
        }

        assertThat(withShooterGoal).isNotNull();
        assertThat(withShooterGoal.homeShooterPlayerId()).isEqualTo(99L);
        assertThat(withShooterGoal.homeRedCardPlayerId()).isNull();
        assertThat(withShooterGoal.homeEffectiveAttack()).isEqualTo(homeTeam.attack());
        assertThat(withShooterGoal.homeEffectiveProtection()).isEqualTo(homeTeam.attackProtection());
    }

    @Test
    void productionSampleRetainsShooterAttemptsEvenWhenPressingStopsEveryGoal() {
        TeamCompartmentAggregator.TeamAggregationResult homeTeam = aggregation(
                10, 5, "Very Easy", new TeamCompartmentAggregator.ShooterProfile(99L, 20, 20));
        TeamCompartmentAggregator.TeamAggregationResult awayTeam = aggregation(
                10, 5, "Very Aggressive", null);
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(new CompartmentEngineConfig());

        CanonicalScoreSampler.GoalSample missed = null;
        for (long seed = 0; seed < 10_000; seed++) {
            CanonicalScoreSampler.GoalSample candidate = sampler.sample(evaluation(homeTeam, awayTeam), seed);
            if (candidate.homeShooterShots() > 0 && candidate.homeShooterGoals() == 0) {
                missed = candidate;
                break;
            }
        }

        assertThat(missed).isNotNull();
        assertThat(missed.homeShooterPlayerId()).isEqualTo(99L);
        assertThat(missed.homeShooterShots()).isPositive();
    }

    @Test
    void passingStyleFiltersCollectiveGoalsAndAddsItsChosenStrikerSeparately() {
        TeamCompartmentAggregator.PassingStyleProfile profile = new TeamCompartmentAggregator.PassingStyleProfile(
                true, 19.0,
                java.util.List.of(
                        new TeamCompartmentAggregator.PassingMidfielder(1, 20, 19, 19),
                        new TeamCompartmentAggregator.PassingMidfielder(2, 19, 19, 19),
                        new TeamCompartmentAggregator.PassingMidfielder(3, 19, 19, 19),
                        new TeamCompartmentAggregator.PassingMidfielder(4, 20, 19, 19),
                        new TeamCompartmentAggregator.PassingMidfielder(5, 20, 19, 19)),
                java.util.List.of(new TeamCompartmentAggregator.PassingStriker(88, 20, 20)));
        TeamCompartmentAggregator.TeamAggregationResult homeTeam = aggregation(
                10, 5, "Short", "Aggressive", "Instantly", profile);
        TeamCompartmentAggregator.TeamAggregationResult awayTeam = aggregation(
                10, 5, "Long", "Normal", "Standard", null);
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(new CompartmentEngineConfig());

        CanonicalScoreSampler.GoalSample withPassingGoal = null;
        for (long seed = 0; seed < 10_000; seed++) {
            CanonicalScoreSampler.GoalSample candidate = sampler.sample(evaluation(homeTeam, awayTeam), seed);
            assertThat(candidate.homePassingControl()).isCloseTo(0.80,
                    org.assertj.core.api.Assertions.within(1e-12));
            assertThat(candidate.awayPassingControl()).isZero();
            assertThat(candidate.homeGoals()).isEqualTo(candidate.homeCollectiveGoals()
                    + candidate.homeShooterGoals() + candidate.homePassingGoals());
            assertThat(candidate.awayGoals()).isEqualTo(candidate.awayCollectiveGoals()
                    + candidate.awayShooterGoals() + candidate.awayPassingGoals());
            if (candidate.homePassingGoals() > 0) {
                withPassingGoal = candidate;
                break;
            }
        }

        assertThat(withPassingGoal).isNotNull();
        assertThat(withPassingGoal.homePassingPlayerId()).isEqualTo(88L);
        assertThat(withPassingGoal.homePassingOpportunities()).isGreaterThanOrEqualTo(
                withPassingGoal.homePassingGoals());
        assertThat(withPassingGoal.homeEffectiveAttack()).isEqualTo(homeTeam.attack());
        assertThat(withPassingGoal.awayEffectiveAttack()).isCloseTo(awayTeam.attack() * 0.20,
                org.assertj.core.api.Assertions.within(1e-12));
    }

    @Test
    void redCardRemovesTheSelectedPlayersAttackAndProtectionBeforeCollectiveScoring() {
        TeamCompartmentAggregator.PlayerBreakdown removable = new TeamCompartmentAggregator.PlayerBreakdown(
                77L, new TeamCompartmentAggregator.LineupSlot(PlayerPosition.MC, 1),
                java.util.List.of(), com.footballmanagergamesimulator.compartment.ForwardInstruction.DEFAULT,
                3, 3, 0, 2, 1, 1, 0, 0,
                0, 0, 0, 3, 2, 3, 2,
                0, TeamCompartmentAggregator.WideChannel.CENTRAL, 0, 0,
                new TeamCompartmentAggregator.ZoneEngagementBreakdown(
                        TeamCompartmentAggregator.ExposureZone.CENTRAL, 1, 1, 0));
        TeamCompartmentAggregator.TeamAggregationResult homeTeam = new TeamCompartmentAggregator.TeamAggregationResult(
                Mentality.BALANCED, 1.0, new TeamCompartmentAggregator.RawTotals(0, 0, 0),
                new TeamCompartmentAggregator.MentalityRedistribution(0, 0, null, null, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                java.util.Map.of(), null,
                new TeamCompartmentAggregator.ExposureBreakdown(0, 0, 0, 1, 5, 5),
                java.util.List.of(removable), 10, 5, "Very Aggressive", null);
        TeamCompartmentAggregator.TeamAggregationResult awayTeam = aggregation(10, 5, "Very Easy", null);
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(new CompartmentEngineConfig());

        CanonicalScoreSampler.GoalSample red = null;
        for (long seed = 0; seed < 10_000; seed++) {
            CanonicalScoreSampler.GoalSample candidate = sampler.sample(evaluation(homeTeam, awayTeam), seed);
            if (candidate.homeRedCardPlayerId() != null) {
                red = candidate;
                break;
            }
        }

        assertThat(red).isNotNull();
        assertThat(red.homeRedCardPlayerId()).isEqualTo(77L);
        assertThat(red.homeEffectiveAttack()).isEqualTo(7.0);
        assertThat(red.homeEffectiveProtection()).isEqualTo(3.0);
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
        return aggregation(attack, protection, "Normal", null);
    }

    private static TeamCompartmentAggregator.TeamAggregationResult aggregation(
            double attack, double protection, String pressing,
            TeamCompartmentAggregator.ShooterProfile shooter) {
        return new TeamCompartmentAggregator.TeamAggregationResult(Mentality.BALANCED, 1.0,
                new TeamCompartmentAggregator.RawTotals(0, 0, 0),
                new TeamCompartmentAggregator.MentalityRedistribution(0, 0, null, null, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                java.util.Map.of(), null,
                new TeamCompartmentAggregator.ExposureBreakdown(0, 0, 0, 1, protection, protection),
                java.util.List.of(), attack, protection, pressing, shooter);
    }

    private static TeamCompartmentAggregator.TeamAggregationResult aggregation(
            double attack, double protection, String passing, String pressing, String recovery,
            TeamCompartmentAggregator.PassingStyleProfile passingStyle) {
        return new TeamCompartmentAggregator.TeamAggregationResult(Mentality.BALANCED, 1.0,
                new TeamCompartmentAggregator.RawTotals(0, 0, 0),
                new TeamCompartmentAggregator.MentalityRedistribution(0, 0, null, null, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                java.util.Map.of(), null,
                new TeamCompartmentAggregator.ExposureBreakdown(0, 0, 0, 1, protection, protection),
                java.util.List.of(), attack, protection, passing, pressing, recovery, null, passingStyle);
    }

    private static CanonicalMatchEvaluation evaluation(
            TeamCompartmentAggregator.TeamAggregationResult homeTeam,
            TeamCompartmentAggregator.TeamAggregationResult awayTeam) {
        GoalProbabilityFormula.GoalDistribution distribution = distribution(new double[]{.5, .5});
        GoalProbabilityFormula.MatchProbability probability = new GoalProbabilityFormula.MatchProbability(
                .5, .5, 1.0, 1.0, distribution, distribution);
        return new CanonicalMatchEvaluation(new CanonicalTeamEvaluation(java.util.List.of(), homeTeam),
                new CanonicalTeamEvaluation(java.util.List.of(), awayTeam), MatchVenue.HOME, 1.0,
                probability, new OutcomeProbability(.3, .4, .3));
    }

    private static GoalProbabilityFormula.GoalDistribution distribution(double[] pmf) {
        return new GoalProbabilityFormula.GoalDistribution(1.0, 1.0, pmf.length - 1, pmf, 0, pmf.length - 1);
    }
}
