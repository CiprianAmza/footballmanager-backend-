package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnderlyingPerformanceServiceTest {

    private final MatchStatsRepository repository = mock(MatchStatsRepository.class);
    private final UnderlyingPerformanceService service = new UnderlyingPerformanceService(repository);

    @Test
    void aggregatesHomeAndAwayMatchesFromTheTeamPerspective() {
        MatchStats homeWin = match(101, 10, 20, 2, 0, 150, 50, 10, 4);
        MatchStats awayDraw = match(102, 30, 10, 1, 1, 100, 100, 8, 5);
        when(repository.findAllByTeam1IdAndSeasonNumber(10, 3)).thenReturn(List.of(homeWin));
        when(repository.findAllByTeam2IdAndSeasonNumber(10, 3)).thenReturn(List.of(awayDraw));

        UnderlyingPerformanceService.UnderlyingPerformance result = service.performance(10, 3);

        assertThat(result.matches()).isEqualTo(2);
        assertThat(result.actualPoints()).isEqualTo(4);
        assertThat(result.goals()).isEqualTo(3);
        assertThat(result.goalsConceded()).isEqualTo(1);
        assertThat(result.xg()).isEqualTo(2.5);
        assertThat(result.xga()).isEqualTo(1.5);
        assertThat(result.finishingDelta()).isEqualTo(0.5);
        assertThat(result.goalsPrevented()).isEqualTo(0.5);
        assertThat(result.actualConversionPercentage()).isEqualTo(20.0);
        assertThat(result.expectedConversionPercentage()).isEqualTo(16.67);
        assertThat(result.conversionDeltaPercentagePoints()).isEqualTo(3.33);
        assertThat(result.xgDifferencePer90()).isEqualTo(0.5);
        assertThat(result.expectedPoints()).isBetween(0.0, 6.0);
        assertThat(result.recentMatches()).hasSize(2);
        assertThat(result.recentMatches().get(1).home()).isFalse();
    }

    @Test
    void returnsStableZeroesBeforeTheFirstMatch() {
        when(repository.findAllByTeam1IdAndSeasonNumber(10, 1)).thenReturn(List.of());
        when(repository.findAllByTeam2IdAndSeasonNumber(10, 1)).thenReturn(List.of());

        UnderlyingPerformanceService.UnderlyingPerformance result = service.performance(10, 1);

        assertThat(result.matches()).isZero();
        assertThat(result.expectedPoints()).isZero();
        assertThat(result.conversionDeltaPercentagePoints()).isZero();
        assertThat(result.xgDifferencePer90()).isZero();
        assertThat(result.confidence()).isEqualTo("LOW");
    }

    @Test
    void expectedPointsFavoursTheSideCreatingTheBetterChances() {
        assertThat(UnderlyingPerformanceService.expectedPoints(2.4, 0.6))
                .isGreaterThan(UnderlyingPerformanceService.expectedPoints(0.6, 2.4));
    }

    private MatchStats match(long id, long homeId, long awayId, int homeGoals, int awayGoals,
                             int homeXg, int awayXg, int homeShots, int awayShots) {
        MatchStats match = new MatchStats();
        match.setId(id);
        match.setTeam1Id(homeId);
        match.setTeam2Id(awayId);
        match.setRoundNumber((int) id - 100);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setHomeXg(homeXg);
        match.setAwayXg(awayXg);
        match.setHomeShots(homeShots);
        match.setAwayShots(awayShots);
        return match;
    }
}
