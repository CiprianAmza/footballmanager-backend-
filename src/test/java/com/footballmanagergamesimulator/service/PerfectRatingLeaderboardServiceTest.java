package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerfectRatingLeaderboardServiceTest {

    private final ScorerRepository scorerRepository = mock(ScorerRepository.class);
    private final HumanRepository humanRepository = mock(HumanRepository.class);
    private final PerfectRatingLeaderboardService service =
            new PerfectRatingLeaderboardService(scorerRepository, humanRepository);

    @Test
    void ranksAllTimePerfectRatingsAndSplitsChampionshipFromCup() {
        List<ScorerRepository.PerfectRatingAggregate> rows = List.of(
                aggregate(11, 101, "Premier League", 1, 2, 81, "North FC", 3, 30),
                aggregate(11, 202, "National Cup", 2, 3, 81, "North FC", 2, 50),
                aggregate(22, 101, "Premier League", 1, 3, 82, "South FC", 4, 60)
        );
        when(scorerRepository.aggregatePerfectRatings()).thenReturn(rows);
        when(humanRepository.findAllById(any())).thenReturn(List.of(human(11, "Alex Ten"), human(22, "Mihai Star")));

        var result = service.leaderboard(null, "ALL", 50);

        assertEquals("ALL", result.scope());
        assertNull(result.competitionId());
        assertEquals(9, result.totalPerfectRatings());
        assertEquals(2, result.totalPlayers());
        assertEquals("Alex Ten", result.leaders().get(0).playerName());
        assertEquals(5, result.leaders().get(0).perfectRatings());
        assertEquals(3, result.leaders().get(0).championshipPerfectRatings());
        assertEquals(2, result.leaders().get(0).cupPerfectRatings());
        assertEquals(2, result.leaders().get(0).competitionCount());
    }

    @Test
    void filtersByCompetitionOrCompetitionLevel() {
        List<ScorerRepository.PerfectRatingAggregate> rows = List.of(
                aggregate(11, 101, "Premier League", 1, 2, 81, "North FC", 3, 30),
                aggregate(11, 202, "National Cup", 2, 3, 81, "North FC", 2, 50),
                aggregate(22, 202, "National Cup", 2, 3, 82, "South FC", 1, 60)
        );
        when(scorerRepository.aggregatePerfectRatings()).thenReturn(rows);
        when(humanRepository.findAllById(any())).thenReturn(List.of(human(11, "Alex Ten"), human(22, "Mihai Star")));

        var cups = service.leaderboard(null, "CUP", 50);
        assertEquals("CUP", cups.scope());
        assertEquals(3, cups.totalPerfectRatings());
        assertEquals(2, cups.totalPlayers());

        var competition = service.leaderboard(101L, "CUP", 50);
        assertEquals("COMPETITION", competition.scope());
        assertEquals("Premier League", competition.competitionName());
        assertEquals(3, competition.totalPerfectRatings());
        assertEquals(1, competition.leaders().size());
    }

    private static Human human(long id, String name) {
        Human human = new Human();
        human.setId(id);
        human.setName(name);
        return human;
    }

    private static ScorerRepository.PerfectRatingAggregate aggregate(
            long playerId, long competitionId, String competitionName, int typeId,
            int season, long teamId, String teamName, long count, long latestRowId) {
        ScorerRepository.PerfectRatingAggregate row = mock(ScorerRepository.PerfectRatingAggregate.class);
        when(row.getPlayerId()).thenReturn(playerId);
        when(row.getCompetitionId()).thenReturn(competitionId);
        when(row.getCompetitionName()).thenReturn(competitionName);
        when(row.getCompetitionTypeId()).thenReturn(typeId);
        when(row.getSeasonNumber()).thenReturn(season);
        when(row.getTeamId()).thenReturn(teamId);
        when(row.getTeamName()).thenReturn(teamName);
        when(row.getPerfectRatings()).thenReturn(count);
        when(row.getLatestRowId()).thenReturn(latestRowId);
        return row;
    }
}
