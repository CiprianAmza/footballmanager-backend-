package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PressingDefenceAnalyticsServiceTest {
    @Test
    void exposesProxyAndKeepsRealPpdaUnavailable() {
        MatchStatsRepository matches = mock(MatchStatsRepository.class);
        DefensivePressureRepository defenceRows = mock(DefensivePressureRepository.class);
        PossessionProgressionRepository progressionRows = mock(PossessionProgressionRepository.class);
        ShotEventRepository shotRows = mock(ShotEventRepository.class);
        PlayerSeasonStatRepository playerStats = mock(PlayerSeasonStatRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        MatchStats match = PossessionProgressionLedgerServiceTest.match();
        match.setAwayXg(85); match.setHomeTackles(20); match.setAwayTackles(15);
        match.setHomeInterceptions(11); match.setAwayInterceptions(8); match.setHomeFouls(10); match.setAwayFouls(13);
        match.setHomeClearances(14); match.setAwayClearances(22); match.setHomeDuelsWon(31); match.setAwayDuelsWon(24);
        when(matches.findAllByTeam1IdAndSeasonNumber(10, 2)).thenReturn(List.of(match));
        when(matches.findAllByTeam2IdAndSeasonNumber(10, 2)).thenReturn(List.of());
        when(defenceRows.findAllByMatchStatsIdOrderByTeamIdAsc(99)).thenReturn(List.of());
        when(progressionRows.findAllByMatchStatsIdOrderByTeamIdAsc(99)).thenReturn(List.of());
        when(shotRows.findAllByMatchStatsIdOrderByShotIndexAsc(99)).thenReturn(List.of());
        when(playerStats.findAllByTeamIdAndSeasonNumber(10, 2)).thenReturn(List.of());
        when(humans.findAllById(any())).thenReturn(List.of());
        DefensivePressureLedgerService ledger = new DefensivePressureLedgerService(defenceRows,
                new PossessionProgressionLedgerService(progressionRows), new ShotEventService(shotRows));
        PressingDefenceAnalyticsService service = new PressingDefenceAnalyticsService(matches, ledger, playerStats, humans);

        PressingDefenceAnalyticsService.PressingDefenceAnalytics result = service.analytics(10, 2);

        assertThat(result.matches()).isEqualTo(1);
        assertThat(result.averages().realPpda()).isNull();
        assertThat(result.averages().realPpdaStatus()).isEqualTo("UNAVAILABLE_REQUIRES_ZONED_EVENTS");
        assertThat(result.averages().ppdaProxy()).isPositive();
        assertThat(result.averages().xgaPerShot()).isEqualTo(.12);
        assertThat(result.totals().clearances()).isEqualTo(14);
        assertThat(result.dataQuality()).anySatisfy(item -> {
            assertThat(item.metric()).isEqualTo("realPpda"); assertThat(item.status()).isEqualTo("UNAVAILABLE");
        });
    }
}
