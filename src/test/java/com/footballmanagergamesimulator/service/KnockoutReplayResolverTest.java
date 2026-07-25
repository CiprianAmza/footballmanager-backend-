package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnockoutReplayResolverTest {
    @Test
    void firstLegHasNoWinnerAndDoesNotAdvance() {
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                1, 77, 10, 20, KnockoutPlanSplit.regularOnly(2, 1), null);

        assertThat(result.score1()).isEqualTo(2);
        assertThat(result.score2()).isEqualTo(1);
        assertThat(result.winnerTeamId()).isNull();
        assertThat(result.decidedBy()).isEqualTo("FIRST_LEG");
        assertThat(result.scoreSuffix()).isEqualTo(" (1st leg)");
    }

    @Test
    void singleLegUsesCanonicalZeroZeroContext() {
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                0, 0, 10, 20, KnockoutPlanSplit.regularOnly(2, 1), null);

        assertThat(result.decidedBy()).isEqualTo("NORMAL");
        assertThat(result.winnerTeamId()).isEqualTo(10L);
    }

    @Test
    void secondLegUsesResolverOrientationAndAggregate() {
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.regularOnly(1, 1), new int[]{0, 1});

        assertThat(result.aggregate1()).isEqualTo(2);
        assertThat(result.aggregate2()).isEqualTo(1);
        assertThat(result.winnerTeamId()).isEqualTo(10L);
        assertThat(result.decidedBy()).isEqualTo("AGGREGATE");
        assertThat(result.scoreSuffix()).isEqualTo(" (agg 2-1)");
    }

    @Test
    void secondLegExtraTimeReplaysWithoutRandomness() {
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.knockout(1, 1, 1, 0, null, null),
                new int[]{0, 0});

        assertThat(result.score1()).isEqualTo(2);
        assertThat(result.score2()).isEqualTo(1);
        assertThat(result.et1()).isEqualTo(1);
        assertThat(result.et2()).isEqualTo(0);
        assertThat(result.decidedBy()).isEqualTo("EXTRA_TIME");
        assertThat(result.winnerTeamId()).isEqualTo(10L);
        assertThat(result.scoreSuffix()).isEqualTo(" (agg 2-1, a.e.t.)");
    }

    @Test
    void secondLegPenaltiesReplaysPenaltyWinnerAndAggregate() {
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.knockout(1, 0, 0, 0, 4, 3),
                new int[]{1, 0});

        assertThat(result.aggregate1()).isEqualTo(1);
        assertThat(result.aggregate2()).isEqualTo(1);
        assertThat(result.penalty1()).isEqualTo(4);
        assertThat(result.penalty2()).isEqualTo(3);
        assertThat(result.decidedBy()).isEqualTo("PENALTIES");
        assertThat(result.winnerTeamId()).isEqualTo(10L);
        assertThat(result.scoreSuffix()).isEqualTo(" (agg 1-1, pens 4-3)");
    }

    @Test
    void missingFirstLegUsesDefensiveSingleLegFallback() {
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.regularOnly(2, 1), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first leg is missing");
    }

    @Test
    void rejectsInvalidFirstLegAndAggregatePhaseCombinations() {
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                1, 77, 10, 20, KnockoutPlanSplit.knockout(1, 1, 1, 0, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                1, 77, 10, 20, KnockoutPlanSplit.knockout(1, 1, 1, 0, 2, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.knockout(2, 0, 0, 0, 4, 3), new int[]{0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.regularOnly(1, 1), new int[]{0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.knockout(1, 1, 1, 1, null, null), new int[]{0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidFirstLegArrays() {
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.regularOnly(1, 1), new int[]{0}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.regularOnly(1, 1), new int[]{0, -1}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void singleLegRequiresEtForLevelScoreAndDecisiveShootoutAfterLevelEt() {
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                0, 0, 10, 20, KnockoutPlanSplit.regularOnly(1, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                0, 0, 10, 20, KnockoutPlanSplit.knockout(1, 1, 0, 0, 4, 3), null);
        assertThat(result.decidedBy()).isEqualTo("PENALTIES");
        assertThat(result.winnerTeamId()).isEqualTo(10L);
    }

    @Test
    void rejectsSingleLegEtWhenNinetyMinutesAlreadyDecided() {
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                0, 0, 10, 20, KnockoutPlanSplit.knockout(2, 1, 1, 0, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTwoLegLevelAfterEtAndRejectsMissingShootout() {
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.knockout(0, 1, 0, 0, 4, 3),
                new int[]{0, 1});
        assertThat(result.decidedBy()).isEqualTo("PENALTIES");
        assertThat(result.winnerTeamId()).isEqualTo(10L);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.knockout(0, 1, 0, 0, null, null),
                new int[]{0, 1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTwoLegDecisionInExtraTimeWithoutShootout() {
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.knockout(1, 0, 1, 0, null, null),
                new int[]{1, 0});
        assertThat(result.decidedBy()).isEqualTo("EXTRA_TIME");
        assertThat(result.winnerTeamId()).isEqualTo(10L);
    }

    @Test
    void rejectsNonCanonicalReplayContexts() {
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                1, 0, 10, 20, KnockoutPlanSplit.regularOnly(1, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                2, 0, 10, 20, KnockoutPlanSplit.regularOnly(1, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                0, 77, 10, 20, KnockoutPlanSplit.regularOnly(1, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                3, 77, 10, 20, KnockoutPlanSplit.regularOnly(1, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                -1, 0, 10, 20, KnockoutPlanSplit.regularOnly(1, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnockoutReplayResolver.resolve(
                0, -1, 10, 20, KnockoutPlanSplit.regularOnly(1, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
