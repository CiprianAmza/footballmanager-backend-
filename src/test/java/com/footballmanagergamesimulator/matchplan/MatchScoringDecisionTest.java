package com.footballmanagergamesimulator.matchplan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchScoringDecisionTest {
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

    private static MatchScoringDecision decision(int home, int away) {
        return new MatchScoringDecision("CTIM:1", 77L, ScoreEngineKind.COMPARTMENT_V1,
                MatchScoringDecision.ALGORITHM_VERSION, "a".repeat(64), "b".repeat(64),
                home, away, 40, 34, 1.2, 0.8);
    }
}
