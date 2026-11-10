package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompetitionGoalsRankingServiceTest {
    private final MatchStatsRepository matches = mock(MatchStatsRepository.class);
    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final CompetitionGoalsRankingService service =
            new CompetitionGoalsRankingService(matches, competitions, gameState);

    @Test
    void ranksByUnroundedGoalsPerMatchAndShowsSampleSize() {
        when(gameState.currentSeason()).thenReturn(13);
        var seasonTotals = List.of(totals(1, 30, 74), totals(2, 10, 25), totals(3, 10, 24));
        when(matches.goalTotalsByCompetitionForSeason(12)).thenReturn(seasonTotals);
        when(competitions.findAllById(any())).thenReturn(List.of(
                competition(1, "League"), competition(2, "Cup"), competition(3, "Stars Cup")));

        var result = service.ranking(12);

        assertEquals(12, result.season());
        assertEquals(List.of(2L, 1L, 3L), result.rows().stream()
                .map(CompetitionGoalsRankingService.Row::competitionId).toList());
        assertEquals(2.5, result.rows().get(0).goalsPerMatch());
        assertEquals(10, result.rows().get(0).matches());
        assertEquals(25, result.rows().get(0).goals());
        assertEquals(List.of(1, 2, 3), result.rows().stream()
                .map(CompetitionGoalsRankingService.Row::rank).toList());
    }

    @Test
    void allTimeUsesAggregatedMatchesAndRejectsFutureSeason() {
        when(gameState.currentSeason()).thenReturn(13);
        var allTimeTotals = List.of(totals(1, 100, 280));
        when(matches.goalTotalsByCompetitionAllTime()).thenReturn(allTimeTotals);
        when(competitions.findAllById(any())).thenReturn(List.of(competition(1, "League")));

        var result = service.ranking(0);

        assertEquals(true, result.allTime());
        assertEquals(2.8, result.rows().get(0).goalsPerMatch());
        assertThrows(IllegalArgumentException.class, () -> service.ranking(14));
        verify(matches, never()).goalTotalsByCompetitionForSeason(14);
    }

    private static MatchStatsRepository.CompetitionGoalTotals totals(long id, long played, long goals) {
        var total = mock(MatchStatsRepository.CompetitionGoalTotals.class);
        when(total.getCompetitionId()).thenReturn(id);
        when(total.getMatches()).thenReturn(played);
        when(total.getGoals()).thenReturn(goals);
        return total;
    }

    private static Competition competition(long id, String name) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setName(name);
        return competition;
    }
}
