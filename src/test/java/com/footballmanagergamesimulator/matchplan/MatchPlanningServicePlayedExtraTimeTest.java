package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The plan of a drawn 2D knockout match, extra time included. */
class MatchPlanningServicePlayedExtraTimeTest {

    private static final long HOME = 10L;
    private static final long AWAY = 20L;

    private final MatchPlanningService planning = new MatchPlanningService(new MatchEngineConfig());

    @Test
    void playedExtraTimeGoalsReplaceThePlannedOnesAndKeepTheirScorers() {
        GoalSlot regular = slot(HOME, 30, GoalPhase.REGULAR_TIME, 101L);
        GoalSlot regularAway = slot(AWAY, 60, GoalPhase.REGULAR_TIME, 201L);
        GoalSlot extra = slot(AWAY, 112, GoalPhase.EXTRA_TIME, 202L);

        MatchPlan plan = planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(extra, regular, regularAway), 0, 1, -1, -1);

        assertThat(plan.getGoalSlots()).containsExactly(regular, regularAway, extra);
        assertThat(plan.getGoalSlots()).extracting(GoalSlot::getSlotIndex).containsExactly(0, 1, 2);
        assertThat(plan.getHomeScore90()).isEqualTo(1);
        assertThat(plan.getAwayScore90()).isEqualTo(1);
        assertThat(plan.hadExtraTime()).isTrue();
        assertThat(plan.getHomeGoals()).isEqualTo(1);
        assertThat(plan.getAwayGoals()).isEqualTo(2);
        assertThat(extra.getScorerId()).isEqualTo(202L);
    }

    @Test
    void aPlayedShootoutAfterAGoallessExtraTimeAddsNoSlot() {
        MatchPlan plan = planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(slot(HOME, 30, GoalPhase.REGULAR_TIME, 101L), slot(AWAY, 60, GoalPhase.REGULAR_TIME, 201L)),
                0, 0, 4, 2);

        assertThat(plan.getGoalSlots()).hasSize(2);
        assertThat(plan.getKnockoutPlanSplit()).isEqualTo(new KnockoutPlanSplit(1, 1, 0, 0, 4, 2));
    }

    @Test
    void playedExtraTimeGoalsMustAddUpToTheExtraTimeScore() {
        assertThatThrownBy(() -> planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(slot(HOME, 100, GoalPhase.EXTRA_TIME, 101L)), 0, 1, -1, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planning.planWithRegularGoals("CTIM:1", 7L, HOME, AWAY,
                List.of(slot(HOME, 100, GoalPhase.EXTRA_TIME, 101L)), -1, -1, -1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GoalSlot slot(long teamId, int minute, GoalPhase phase, long scorerId) {
        GoalSlot slot = new GoalSlot(teamId, minute, phase, "OPEN_PLAY");
        slot.resolve(scorerId, null);
        return slot;
    }
}
