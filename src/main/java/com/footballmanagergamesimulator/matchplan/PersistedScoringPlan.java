package com.footballmanagergamesimulator.matchplan;

import java.util.Objects;

/** The immutable scoring decision and knockout split adopted from persistence. */
public record PersistedScoringPlan(
        MatchScoringDecision decision,
        KnockoutPlanSplit knockoutPlanSplit,
        long homeTeamId,
        long awayTeamId) {
    public PersistedScoringPlan {
        decision = Objects.requireNonNull(decision, "decision");
        knockoutPlanSplit = Objects.requireNonNull(knockoutPlanSplit, "knockoutPlanSplit");
        if (homeTeamId <= 0 || awayTeamId <= 0 || homeTeamId == awayTeamId) {
            throw new IllegalArgumentException("team ids must be distinct positive values");
        }
    }
}
