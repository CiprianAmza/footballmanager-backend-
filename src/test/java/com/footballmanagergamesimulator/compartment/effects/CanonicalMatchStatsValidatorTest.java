package com.footballmanagergamesimulator.compartment.effects;

import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class CanonicalMatchStatsValidatorTest {
    @Test
    void acceptsExactlyThePersistedFootballGoals() {
        CanonicalMatchEffectsInput input = input(
                KnockoutPlanSplit.regularOnly(2, 1),
                List.of(goal(0, 10, 10, 101), goal(1, 20, 20, 201), goal(2, 30, 10, 102),
                        assist(0, 10, 10, 100)));

        assertThatCode(() -> CanonicalMatchStatsValidator.validate(input)).doesNotThrowAnyException();
    }

    @Test
    void validatesExtraTimeGoalsButExcludesShootoutGoals() {
        CanonicalMatchEffectsInput extraTime = input(
                KnockoutPlanSplit.knockout(1, 1, 1, 0, null, null),
                List.of(goal(0, 10, 10, 101), goal(1, 20, 20, 201), goal(2, 100, 10, 102)));
        CanonicalMatchStatsValidator.validate(extraTime);

        CanonicalMatchEffectsInput shootout = input(
                KnockoutPlanSplit.knockout(1, 1, 0, 0, 5, 4),
                List.of(goal(0, 10, 10, 101), goal(1, 20, 20, 201)));
        CanonicalMatchStatsValidator.validate(shootout);
    }

    @Test
    void rejectsContradictoryEffects() {
        assertThatThrownBy(() -> CanonicalMatchStatsValidator.validate(input(
                KnockoutPlanSplit.regularOnly(1, 0), List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalMatchStatsValidator.validate(input(
                KnockoutPlanSplit.regularOnly(1, 0), List.of(assist(0, 10, 10, 100)))) )
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalMatchStatsValidator.validate(input(
                KnockoutPlanSplit.regularOnly(1, 0), List.of(goal(0, 10, 10, 101), goal(0, 10, 10, 102)))) )
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalMatchStatsValidator.validate(input(
                KnockoutPlanSplit.regularOnly(1, 0),
                List.of(goal(0, 10, 10, 101), assist(0, 10, 10, 100), assist(0, 10, 10, 99)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalMatchStatsValidator.validate(input(
                KnockoutPlanSplit.regularOnly(1, 0),
                List.of(goal(0, 10, 10, 101), assist(0, 10, 10, 101)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalMatchEffectsInput(decision(1, 0), KnockoutPlanSplit.regularOnly(1, 0),
                10, 20, List.of(goal(0, 10, 99, 101))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalMatchEffectsInput(decision(1, 0), KnockoutPlanSplit.regularOnly(0, 0),
                10, 20, List.of(goal(0, 10, 10, 101))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CanonicalMatchEffectsInput input(KnockoutPlanSplit split,
                                                     List<CanonicalMatchEffectEvent> events) {
        return new CanonicalMatchEffectsInput(decision(split.score90Home(), split.score90Away()), split, 10, 20, events);
    }

    private static MatchScoringDecision decision(int home, int away) {
        return new MatchScoringDecision("CTIM:1", 7L, ScoreEngineKind.COMPARTMENT_V1,
                ScoreEngineKind.COMPARTMENT_V1.algorithmVersion(), "a".repeat(64), "b".repeat(64),
                home, away, 5_000, 4_000, null, null);
    }

    private static CanonicalMatchEffectEvent goal(int slot, int minute, long team, long player) {
        return new CanonicalMatchEffectEvent(slot, minute, team, player, "goal");
    }

    private static CanonicalMatchEffectEvent assist(int slot, int minute, long team, long player) {
        return new CanonicalMatchEffectEvent(slot, minute, team, player, "assist");
    }
}
