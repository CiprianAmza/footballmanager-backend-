package com.footballmanagergamesimulator.compartment.match;

import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;

import java.util.Objects;

public record CanonicalMatchEvaluation(
        CanonicalTeamEvaluation home,
        CanonicalTeamEvaluation away,
        MatchVenue venue,
        double combinedOpenness,
        GoalProbabilityFormula.MatchProbability probability,
        OutcomeProbability outcome) {
    public CanonicalMatchEvaluation {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(away, "away");
        Objects.requireNonNull(venue, "venue");
        if (!Double.isFinite(combinedOpenness) || combinedOpenness < 0.0) {
            throw new IllegalArgumentException("combinedOpenness must be finite and non-negative");
        }
        Objects.requireNonNull(probability, "probability");
        Objects.requireNonNull(outcome, "outcome");
    }
}
