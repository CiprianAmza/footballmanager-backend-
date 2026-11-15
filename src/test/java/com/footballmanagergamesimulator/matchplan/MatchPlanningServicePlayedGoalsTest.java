package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.match2d.Match2dMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The plan of a match drawn by the 2D engine (the classic result, reproduced). */
class MatchPlanningServicePlayedGoalsTest {

    private static final long HOME = 10L;
    private static final long AWAY = 20L;

    private final MatchPlanningService planning = new MatchPlanningService(new MatchEngineConfig());

    @Test
    void playedGoalsAreOrderedByMinuteIndexedAndKeepTheirScorers() {
        GoalSlot late = resolved(HOME, 80, "HEADER", 101L);
        GoalSlot ownGoal = resolved(AWAY, 30, Match2dMapper.OWN_GOAL, 102L);
        GoalSlot open = new GoalSlot(HOME, 30, GoalPhase.REGULAR_TIME, "OPEN_PLAY");

        MatchPlan plan = planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(late, ownGoal, open), -1, -1, -1, -1);

        assertThat(plan.getGoalSlots()).containsExactly(ownGoal, open, late);   // stable for minute 30
        assertThat(plan.getGoalSlots()).extracting(GoalSlot::getSlotIndex).containsExactly(0, 1, 2);
        assertThat(plan.getHomeScore90()).isEqualTo(2);
        assertThat(plan.getAwayScore90()).isEqualTo(1);
        assertThat(plan.hadExtraTime()).isFalse();
        assertThat(ownGoal.getScorerId()).isEqualTo(102L);
        assertThat(open.isResolved()).isFalse();
        assertThat(plan.getGoalSlots()).allMatch(slot -> slot.getMatchPlan() == plan);
        assertThat(plan.getAlgorithmVersion()).isEqualTo(MatchPlanningService.ALGORITHM_VERSION);
    }

    @Test
    void anExtraTimeScoreWithoutItsPlayedGoalsIsRefused() {
        // The drawn match reproduces extra time too, so its goals are never planned apart.
        assertThatThrownBy(() -> planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(resolved(HOME, 44, "OPEN_PLAY", 101L), resolved(AWAY, 89, "OPEN_PLAY", 201L)),
                1, 0, -1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMatch2dDecisionAppliesToThePlayedPlanWithoutForcingAnySlot() throws Exception {
        com.footballmanagergamesimulator.match2d.Match2dResult result = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"score\":[1,1],\"engineVersion\":\"1\",\"goals\":[]}",
                        com.footballmanagergamesimulator.match2d.Match2dResult.class);
        MatchScoringDecision decision = Match2dMapper.decision("CTIM:1", 7L, result, "{}", 1.0, 1.0);
        MatchPlan plan = planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(resolved(HOME, 10, "OPEN_PLAY", 101L), new GoalSlot(AWAY, 20, GoalPhase.REGULAR_TIME, "PENALTY")),
                -1, -1, -1, -1);

        plan.applyScoreDecision(decision);

        assertThat(plan.getScoreEngine()).isEqualTo(ScoreEngineKind.MATCH2D_V1);
        assertThat(plan.getScoreDecision()).isEqualTo(decision);
        assertThat(plan.getGoalSlots()).allMatch(slot -> slot.getForcedScorerId() == null);
        assertThat(plan.getKnockoutPlanSplit()).isEqualTo(KnockoutPlanSplit.regularOnly(1, 1));
    }

    @Test
    void aPlayedGoalOfAnotherTeamOrPhaseIsRefused() {
        assertThatThrownBy(() -> planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(new GoalSlot(99L, 10, GoalPhase.REGULAR_TIME, "OPEN_PLAY")), -1, -1, -1, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(new GoalSlot(HOME, 100, GoalPhase.EXTRA_TIME, "OPEN_PLAY")), -1, -1, -1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GoalSlot resolved(long teamId, int minute, String type, long scorerId) {
        GoalSlot slot = new GoalSlot(teamId, minute, GoalPhase.REGULAR_TIME, type);
        slot.resolve(scorerId, null);
        return slot;
    }
}
