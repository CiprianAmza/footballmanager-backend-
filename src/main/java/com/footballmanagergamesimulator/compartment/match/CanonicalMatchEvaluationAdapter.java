package com.footballmanagergamesimulator.compartment.match;

import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class CanonicalMatchEvaluationAdapter {
    private final CanonicalTeamEvaluationAdapter teamAdapter;
    private final GoalProbabilityFormula probabilityFormula;

    public CanonicalMatchEvaluationAdapter(CompartmentEngineConfig compartmentConfig,
                                           MatchEngineConfig matchEngineConfig) {
        this.teamAdapter = new CanonicalTeamEvaluationAdapter(
                Objects.requireNonNull(compartmentConfig, "compartmentConfig"),
                Objects.requireNonNull(matchEngineConfig, "matchEngineConfig"));
        this.probabilityFormula = new GoalProbabilityFormula(compartmentConfig);
    }

    public CanonicalMatchEvaluation evaluate(CanonicalRuntimeTeamInput home,
                                              CanonicalRuntimeTeamInput away,
                                              MatchVenue venue) {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(away, "away");
        Objects.requireNonNull(venue, "venue");
        Set<Long> homeIds = home.lineup().stream().map(player -> player.playerId()).collect(java.util.stream.Collectors.toSet());
        Set<Long> overlapping = new HashSet<>(homeIds);
        overlapping.retainAll(away.lineup().stream().map(player -> player.playerId()).collect(java.util.stream.Collectors.toSet()));
        if (!overlapping.isEmpty()) {
            throw new IllegalArgumentException("player ids must be unique across teams: " + overlapping);
        }

        return evaluate(evaluateTeam(home), evaluateTeam(away), venue);
    }

    /** Evaluate a fixed XI once so high-volume read-only consumers can reuse it across matchups. */
    public CanonicalTeamEvaluation evaluateTeam(CanonicalRuntimeTeamInput input) {
        Objects.requireNonNull(input, "input");
        return teamAdapter.evaluate(input.mentality(), input.lineup(), input.tacticalContexts());
    }

    /** Combine two already-evaluated teams without repeating player/compartment calculations. */
    public CanonicalMatchEvaluation evaluate(CanonicalTeamEvaluation homeEvaluation,
                                              CanonicalTeamEvaluation awayEvaluation,
                                              MatchVenue venue) {
        Objects.requireNonNull(homeEvaluation, "homeEvaluation");
        Objects.requireNonNull(awayEvaluation, "awayEvaluation");
        Objects.requireNonNull(venue, "venue");
        double combinedOpenness = (homeEvaluation.team().openness() + awayEvaluation.team().openness()) / 2.0;
        GoalProbabilityFormula.MatchProbability probability = probabilityFormula.expectedGoals(
                homeEvaluation.team().attack(), awayEvaluation.team().attackProtection(),
                awayEvaluation.team().attack(), homeEvaluation.team().attackProtection(),
                combinedOpenness, venue == MatchVenue.HOME);
        OutcomeProbability outcome = outcomeProbability(probability);
        return new CanonicalMatchEvaluation(homeEvaluation, awayEvaluation, venue,
                combinedOpenness, probability, outcome);
    }

    private static OutcomeProbability outcomeProbability(GoalProbabilityFormula.MatchProbability probability) {
        double homeWin = 0.0;
        double draw = 0.0;
        double awayWin = 0.0;
        double[] home = probability.homeGoals().probabilities();
        double[] away = probability.awayGoals().probabilities();
        for (int homeGoals = 0; homeGoals < home.length; homeGoals++) {
            for (int awayGoals = 0; awayGoals < away.length; awayGoals++) {
                double mass = home[homeGoals] * away[awayGoals];
                if (homeGoals > awayGoals) homeWin += mass;
                else if (homeGoals == awayGoals) draw += mass;
                else awayWin += mass;
            }
        }
        return new OutcomeProbability(homeWin, draw, awayWin);
    }
}
