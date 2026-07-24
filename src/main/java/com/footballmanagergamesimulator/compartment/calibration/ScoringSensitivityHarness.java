package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;

import java.util.Objects;

/** Pure 38-match/season harness. No Spring, repositories, clock, or domain entities. */
public final class ScoringSensitivityHarness {
    private final CanonicalMatchEvaluationAdapter adapter;
    private final CanonicalScoreSampler sampler;

    public ScoringSensitivityHarness(CanonicalMatchEvaluationAdapter adapter, CanonicalScoreSampler sampler) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    public ScoringSensitivityResult run(ScoringSensitivityScenario scenario, String weightKey,
                                       double baselineValue, double testedValue) {
        Objects.requireNonNull(scenario, "scenario");
        int points = 0, goalsFor = 0, goalsAgainst = 0, wins = 0, draws = 0, losses = 0;
        double xgFor = 0.0, xgAgainst = 0.0;
        int matches = scenario.seasons() * 38;
        for (int match = 0; match < matches; match++) {
            boolean home = match % 2 < 19;
            CanonicalRuntimeTeamInput mine = home ? scenario.baselineTeam() : scenario.opponent();
            CanonicalRuntimeTeamInput theirs = home ? scenario.opponent() : scenario.baselineTeam();
            CanonicalMatchEvaluation evaluation = adapter.evaluate(mine, theirs, MatchVenue.HOME);
            CanonicalScoreSampler.GoalSample sample = sampler.sample(evaluation, scenario.seed() + match);
            int own = home ? sample.homeGoals() : sample.awayGoals();
            int opp = home ? sample.awayGoals() : sample.homeGoals();
            goalsFor += own; goalsAgainst += opp;
            xgFor += home ? evaluation.probability().homeXg() : evaluation.probability().awayXg();
            xgAgainst += home ? evaluation.probability().awayXg() : evaluation.probability().homeXg();
            if (own > opp) { points += 3; wins++; }
            else if (own == opp) { points++; draws++; }
            else losses++;
        }
        double average = points / (double) scenario.seasons();
        return new ScoringSensitivityResult(weightKey, baselineValue, testedValue, average, average - 60.0,
                goalsFor, goalsAgainst, xgFor, xgAgainst, wins, draws, losses,
                0.0, 0.0, 0.0, 0.0, 0.0, matches);
    }
}
