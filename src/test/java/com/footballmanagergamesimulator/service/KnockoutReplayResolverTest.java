package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
                2, 77, 10, 20, KnockoutPlanSplit.knockout(1, 1, null, null, 4, 3),
                new int[]{0, 1});

        assertThat(result.aggregate1()).isEqualTo(2);
        assertThat(result.aggregate2()).isEqualTo(1);
        assertThat(result.penalty1()).isEqualTo(4);
        assertThat(result.penalty2()).isEqualTo(3);
        assertThat(result.decidedBy()).isEqualTo("PENALTIES");
        assertThat(result.winnerTeamId()).isEqualTo(10L);
        assertThat(result.scoreSuffix()).isEqualTo(" (agg 2-1, pens 4-3)");
    }

    @Test
    void missingFirstLegUsesDefensiveSingleLegFallback() {
        KnockoutReplayResolver.Result result = KnockoutReplayResolver.resolve(
                2, 77, 10, 20, KnockoutPlanSplit.regularOnly(2, 1), null);

        assertThat(result.aggregate1()).isNull();
        assertThat(result.aggregate2()).isNull();
        assertThat(result.winnerTeamId()).isEqualTo(10L);
        assertThat(result.decidedBy()).isEqualTo("NORMAL");
        assertThat(result.scoreSuffix()).isEmpty();
    }
}
