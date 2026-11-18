package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.ExpectedPoints;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The engine's pre-sampling expectation (xG, W/D/L, xPts) against its own sampler. */
class CanonicalMatchExpectationTest {

    private static CanonicalMatchEvaluation match(CompartmentEngineConfig config, Map<String, String> home,
                                                  double homeLevel, boolean homeShooter) {
        CanonicalMatchEvaluationAdapter adapter = new CanonicalMatchEvaluationAdapter(config, new MatchEngineConfig());
        return adapter.evaluate(adapter.evaluateTeam(TacticCombinationCalibration.team(1, home, homeLevel, homeShooter)),
                adapter.evaluateTeam(TacticCombinationCalibration.team(101, Map.of(), 12.0, false)), MatchVenue.HOME);
    }

    private static CompartmentEngineConfig withoutRedCards() {
        CompartmentEngineConfig config = CompartmentConfigFixture.load();
        config.getShooter().getPressing().values().forEach(rule -> rule.setRedCardChance(0.0));
        return config;
    }

    @Test
    void expectationIsAProperDistributionAndDeterministic() {
        CompartmentEngineConfig config = CompartmentConfigFixture.load();
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(config);
        CanonicalMatchEvaluation evaluation = match(config, Map.of(), 12.0, false);
        CanonicalScoreSampler.MatchExpectation e = sampler.expect(evaluation);
        assertThat(Arrays.stream(e.homeGoals()).sum()).isCloseTo(1.0, within(1e-9));
        assertThat(Arrays.stream(e.awayGoals()).sum()).isCloseTo(1.0, within(1e-9));
        assertThat(e.homeWin() + e.draw() + e.awayWin()).isCloseTo(1.0, within(1e-9));
        assertThat(e.homeXg()).isCloseTo(ExpectedPoints.mean(e.homeGoals()), within(1e-9));
        assertThat(e.homeXpts()).isBetween(0.0, 3.0);
        assertThat(e.awayXpts()).isBetween(0.0, 3.0);
        assertThat(e.homeXpts() + e.awayXpts()).isCloseTo(3.0 - e.draw(), within(1e-9));
        // Equal teams: home advantage only.
        assertThat(e.homeXpts()).isGreaterThan(e.awayXpts());

        CanonicalScoreSampler.MatchExpectation again = sampler.expect(evaluation);
        assertThat(again.homeXg()).isEqualTo(e.homeXg());
        assertThat(again.homeXpts()).isEqualTo(e.homeXpts());
        // Expectation does not depend on (or touch) the sampled score.
        assertThat(sampler.sample(evaluation, 42L)).isEqualTo(sampler.sample(evaluation, 42L));
    }

    @Test
    void withoutRedCardsTheShooterAddsExactlyHisExpectedGoals() {
        CompartmentEngineConfig config = withoutRedCards();
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(config);
        CanonicalScoreSampler.MatchExpectation plain = sampler.expect(match(config, Map.of(), 12.0, false));
        CanonicalMatchEvaluation shooterMatch = match(config, Map.of(), 12.0, true);
        CanonicalScoreSampler.MatchExpectation shooter = sampler.expect(shooterMatch);
        // The SHOOTER side's collective xG changes (the SHOOTER gives up ordinary work), so compare
        // with the formula's own collective mean plus the SHOOTER's expected goals.
        double[] collective = shooterMatch.probability().homeGoals().probabilities();
        double shotsMean = 0.0;
        java.util.List<Double> shots = config.getShooter().getExceptionalPositioningShotDistribution();
        for (int k = 0; k < shots.size(); k++) shotsMean += k * shots.get(k);
        double chance = 0.80 * (1.0 - 0.50); // Long Shots 20, opponent pressing Normal
        assertThat(shooter.homeXg()).isCloseTo(ExpectedPoints.mean(collective) + shotsMean * chance, within(1e-9));
        assertThat(shooter.homeXpts()).isGreaterThan(plain.homeXpts());
    }

    @Test
    void expectationMatchesTheSamplerIncludingShooterCombinationsAndRedCards() {
        CompartmentEngineConfig config = CompartmentConfigFixture.load();
        CanonicalScoreSampler sampler = new CanonicalScoreSampler(config);
        Map<String, String> top = new java.util.HashMap<>(Map.of("inPossession", "Keep Ball", "passingType", "Short",
                "tempo", "Lower", "pressing", "Aggressive", "recovery", "Very Fast", "width", "Wide", "widePlay", "Cross"));
        for (Map<String, String> tactic : java.util.List.of(Map.<String, String>of(), top)) {
            CanonicalMatchEvaluation evaluation = match(config, tactic, 12.0, true);
            CanonicalScoreSampler.MatchExpectation e = sampler.expect(evaluation);
            int n = 20_000;
            double homeGoals = 0, awayGoals = 0, homePoints = 0;
            for (int seed = 0; seed < n; seed++) {
                CanonicalScoreSampler.GoalSample s = sampler.sample(evaluation, 1_000_003L * seed + 11);
                homeGoals += s.homeGoals();
                awayGoals += s.awayGoals();
                homePoints += s.homeGoals() > s.awayGoals() ? 3 : s.homeGoals() == s.awayGoals() ? 1 : 0;
            }
            assertThat(homeGoals / n).isCloseTo(e.homeXg(), within(0.05));
            assertThat(awayGoals / n).isCloseTo(e.awayXg(), within(0.05));
            assertThat(homePoints / n).isCloseTo(e.homeXpts(), within(0.05));
        }
    }

    @Test
    void passingStyleErasureAndStrikerChancesAreInTheExpectation() {
        CompartmentEngineConfig on = withoutRedCards();
        CompartmentEngineConfig off = withoutRedCards();
        off.getTacticCombinations().setEnabled(false);
        Map<String, String> passing = Map.of("passingType", "Short", "pressing", "Aggressive", "recovery", "Very Fast");
        CanonicalMatchEvaluation withStyle = match(on, passing, 12.0, false);
        CanonicalScoreSampler.MatchExpectation e = new CanonicalScoreSampler(on).expect(withStyle);
        CompartmentEngineConfig.PassingMechanic rules =
                on.getTacticCombinations().getCombinations().get("PASSING_STYLE").getPassingStyle();
        double keep = 1.0 - rules.getSuppression();
        double opponentCollective = ExpectedPoints.mean(withStyle.probability().awayGoals().probabilities());
        double ownCollective = ExpectedPoints.mean(withStyle.probability().homeGoals().probabilities());
        assertThat(e.awayXg()).isCloseTo(opponentCollective * keep, within(1e-9));
        assertThat(e.homeXg()).isCloseTo(ownCollective + rules.meanOpportunities() * rules.getStrikerGoalChance(),
                within(1e-9));
    }
}
