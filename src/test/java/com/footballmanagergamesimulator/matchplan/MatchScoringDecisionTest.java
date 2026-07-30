package com.footballmanagergamesimulator.matchplan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchScoringDecisionTest {
    @Test
    void everyEngineHasOneCanonicalAlgorithmVersion() {
        assertThat(ScoreEngineKind.values()).hasSize(2);
        assertThat(ScoreEngineKind.ADMIN_OVERRIDE.algorithmVersion()).isEqualTo("admin-override-1");
        assertThat(ScoreEngineKind.COMPARTMENT_V1.algorithmVersion()).isEqualTo("compartment-score-1");
        assertThat(ScoreEngineKind.values()).containsExactly(
                ScoreEngineKind.ADMIN_OVERRIDE, ScoreEngineKind.COMPARTMENT_V1);
    }

    @Test
    void engineAndAlgorithmVersionCannotContradictEachOther() {
        assertThatThrownBy(() -> new MatchScoringDecision("CTIM:1", 1L,
                ScoreEngineKind.ADMIN_OVERRIDE, ScoreEngineKind.COMPARTMENT_V1.algorithmVersion(),
                "a".repeat(64), "b".repeat(64), 1, 0, 1, 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void decisionIsImmutableAcrossEveryPlanStatus() {
        MatchScoringDecision decision = decision(2, 1);
        MatchPlan plan = new MatchPlan("CTIM:1", decision.seed(), "matchplan-2", 10, 20,
                2, 1, -1, -1, -1, -1, List.of());
        plan.applyScoreDecision(decision);
        for (MatchPlan.Status status : MatchPlan.Status.values()) {
            plan.setStatus(status);
            plan.applyScoreDecision(decision);
            assertThat(plan.getScoreDecision()).isEqualTo(decision);
        }
        assertThatThrownBy(() -> plan.applyScoreDecision(decision(3, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passingStyleGoalsArePersistedAndForcedOntoTheChosenStriker() {
        GoalSlot first = new GoalSlot(10, 20, GoalPhase.REGULAR_TIME, "OPEN_PLAY");
        GoalSlot second = new GoalSlot(10, 70, GoalPhase.REGULAR_TIME, "OPEN_PLAY");
        first.setSlotIndex(0);
        second.setSlotIndex(1);
        MatchPlan plan = new MatchPlan("CTIM:2", 88L, "matchplan-2", 10, 20,
                2, 0, -1, -1, -1, -1, new java.util.ArrayList<>(List.of(first, second)));
        MatchScoringDecision decision = new MatchScoringDecision(
                "CTIM:2", 88L, ScoreEngineKind.COMPARTMENT_V1,
                MatchScoringDecision.ALGORITHM_VERSION, "a".repeat(64), "b".repeat(64),
                2, 0, 40, 34, 1.2, 0.3,
                1, 0, null, null, 0, 0, null, null, 0, 0,
                88L, null, 1, 0, 3, 0, 0.80, 0.0);

        plan.applyScoreDecision(decision);

        assertThat(plan.getHomePassingPlayerId()).isEqualTo(88L);
        assertThat(plan.getHomePassingGoals()).isEqualTo(1);
        assertThat(plan.getHomePassingOpportunities()).isEqualTo(3);
        assertThat(plan.getHomePassingControl()).isEqualTo(0.80);
        assertThat(first.getForcedScorerId()).isEqualTo(88L);
        assertThat(first.getGoalType()).isEqualTo("PASSING_STYLE");
        assertThat(second.getForcedScorerId()).isNull();
        assertThat(plan.getScoreDecision()).isEqualTo(decision);
    }

    private static MatchScoringDecision decision(int home, int away) {
        return new MatchScoringDecision("CTIM:1", 77L, ScoreEngineKind.COMPARTMENT_V1,
                MatchScoringDecision.ALGORITHM_VERSION, "a".repeat(64), "b".repeat(64),
                home, away, 40, 34, 1.2, 0.8);
    }
}
