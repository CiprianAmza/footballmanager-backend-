package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScorerLeaderboardSyncServiceTest {

    private HumanRepository humanRepository;
    private TeamRepository teamRepository;
    private ScorerRepository scorerRepository;
    private ScorerLeaderboardRepository leaderboardRepository;
    private RoundRepository roundRepository;
    private ScorerLeaderboardSyncService service;

    @BeforeEach
    void setUp() {
        humanRepository = mock(HumanRepository.class);
        teamRepository = mock(TeamRepository.class);
        scorerRepository = mock(ScorerRepository.class);
        leaderboardRepository = mock(ScorerLeaderboardRepository.class);
        roundRepository = mock(RoundRepository.class);
        service = new ScorerLeaderboardSyncService(
                humanRepository,
                teamRepository,
                scorerRepository,
                leaderboardRepository,
                roundRepository);
        com.footballmanagergamesimulator.model.Round round =
                new com.footballmanagergamesimulator.model.Round();
        round.setSeason(17);
        when(roundRepository.findById(1L)).thenReturn(java.util.Optional.of(round));
    }

    @Test
    void repairsMissingPlayerFromAppearancesAndRefreshesRetiredPlayerAge() {
        Human retired = player(107L, "Kvekrpur", null, 36, 366, true);
        retired.setBestEverRating(369);
        retired.setSeasonOfBestEverRating(13);
        Human missing = player(4058L, "Saviola", 87L, 27, 300, false);
        missing.setBestEverRating(300);
        missing.setSeasonOfBestEverRating(6);

        ScorerLeaderboardEntry oldKvekrpur = new ScorerLeaderboardEntry();
        oldKvekrpur.setPlayerId(107L);
        oldKvekrpur.setName("Kvekrpur");
        oldKvekrpur.setPosition("ST");
        oldKvekrpur.setAge(20);
        oldKvekrpur.setCurrentRating(366);
        oldKvekrpur.setBestEverRating(369);
        oldKvekrpur.setSeasonOfBestEverRating(12);
        oldKvekrpur.setTeamId(14L);
        oldKvekrpur.setTeamName("Tik Tok");
        oldKvekrpur.setActive(false);

        Team team = new Team();
        team.setId(87L);
        team.setName("Inazuma Japan");

        ScorerRepository.LeaderboardAggregate aggregate = aggregateForSaviola();
        when(humanRepository.findAllByTypeId(1L)).thenReturn(List.of(retired, missing));
        when(leaderboardRepository.findAll()).thenReturn(List.of(oldKvekrpur));
        when(teamRepository.findAll()).thenReturn(List.of(team));
        when(scorerRepository.aggregateAllForLeaderboard(17)).thenReturn(List.of(aggregate));

        Map<Long, ScorerLeaderboardEntry> result = service.synchronizeAllPlayers();

        ScorerLeaderboardEntry repaired = result.get(4058L);
        assertEquals("Saviola", repaired.getName());
        assertEquals(300, repaired.getBestEverRating());
        assertEquals(540, repaired.getMatches());
        assertEquals(100, repaired.getGoals());
        assertEquals(380, repaired.getLeagueMatches());
        assertEquals(74, repaired.getLeagueGoals());
        assertEquals("Inazuma Japan", repaired.getTeamName());
        assertTrue(repaired.isActive());

        ScorerLeaderboardEntry refreshedRetired = result.get(107L);
        assertEquals(36, refreshedRetired.getAge());
        assertEquals("Tik Tok", refreshedRetired.getTeamName(),
                "all-time rows retain the last club after retirement");
        assertFalse(refreshedRetired.isActive());
        verify(leaderboardRepository).saveAll(anyList());
    }

    @Test
    void skipsHistoricalAggregationWhenEveryPlayerAlreadyHasAValidRow() {
        Human player = player(1L, "Existing", 2L, 25, 180, false);
        ScorerLeaderboardEntry entry = new ScorerLeaderboardEntry();
        entry.setPlayerId(1L);
        entry.setName("Existing");
        entry.setPosition("ST");
        entry.setAge(25);
        entry.setCurrentRating(180);
        entry.setBestEverRating(180);
        entry.setSeasonOfBestEverRating(17);
        entry.setTeamId(2L);
        entry.setTeamName("Club");
        entry.setActive(true);
        Team team = new Team();
        team.setId(2L);
        team.setName("Club");
        when(humanRepository.findAllByTypeId(1L)).thenReturn(List.of(player));
        when(leaderboardRepository.findAll()).thenReturn(List.of(entry));
        when(teamRepository.findAll()).thenReturn(List.of(team));

        service.synchronizeAllPlayers();

        verify(scorerRepository, never()).aggregateAllForLeaderboard(17);
        verify(leaderboardRepository, never()).saveAll(anyList());
    }

    private Human player(long id,
                         String name,
                         Long teamId,
                         int age,
                         double rating,
                         boolean retired) {
        Human player = new Human();
        player.setId(id);
        player.setName(name);
        player.setTeamId(teamId);
        player.setTypeId(1L);
        player.setPosition("ST");
        player.setAge(age);
        player.setRating(rating);
        player.setBestEverRating(rating);
        player.setSeasonOfBestEverRating(17);
        player.setRetired(retired);
        return player;
    }

    private ScorerRepository.LeaderboardAggregate aggregateForSaviola() {
        ScorerRepository.LeaderboardAggregate aggregate =
                mock(ScorerRepository.LeaderboardAggregate.class);
        when(aggregate.getPlayerId()).thenReturn(4058L);
        when(aggregate.getMatches()).thenReturn(540L);
        when(aggregate.getGoals()).thenReturn(100L);
        when(aggregate.getLeagueMatches()).thenReturn(380L);
        when(aggregate.getLeagueGoals()).thenReturn(74L);
        when(aggregate.getCupMatches()).thenReturn(40L);
        when(aggregate.getCupGoals()).thenReturn(10L);
        when(aggregate.getSecondLeagueMatches()).thenReturn(0L);
        when(aggregate.getSecondLeagueGoals()).thenReturn(0L);
        when(aggregate.getCurrentSeasonGames()).thenReturn(0L);
        when(aggregate.getCurrentSeasonGoals()).thenReturn(0L);
        when(aggregate.getCurrentSeasonLeagueGames()).thenReturn(0L);
        when(aggregate.getCurrentSeasonLeagueGoals()).thenReturn(0L);
        when(aggregate.getCurrentSeasonCupGames()).thenReturn(0L);
        when(aggregate.getCurrentSeasonCupGoals()).thenReturn(0L);
        when(aggregate.getCurrentSeasonSecondLeagueGames()).thenReturn(0L);
        when(aggregate.getCurrentSeasonSecondLeagueGoals()).thenReturn(0L);
        return aggregate;
    }
}
