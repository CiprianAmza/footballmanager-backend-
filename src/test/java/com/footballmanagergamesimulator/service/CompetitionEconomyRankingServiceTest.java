package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompetitionEconomyRankingServiceTest {
    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
    private final CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
    private final FinancialRecordRepository finances = mock(FinancialRecordRepository.class);
    private final HumanRepository humans = mock(HumanRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final CompetitionEconomyRankingService service = new CompetitionEconomyRankingService(
            competitions, entries, results, finances, humans, teams, gameState);

    @Test
    void ranksObservedCrowdsAndCurrentSquadWagesAcrossLeagueAndCup() {
        when(gameState.currentSeason()).thenReturn(7);
        when(competitions.findAll()).thenReturn(List.of(competition(1, "First League"), competition(2, "National Cup")));
        when(teams.findAll()).thenReturn(List.of(team(10, "Alpha", 1), team(20, "Beta", 1)));
        when(entries.findAllBySeasonNumber(7)).thenReturn(List.of(entry(2, 10)));
        when(results.findAllBySeasonNumber(7)).thenReturn(List.of(result(2, 10, 20)));
        var teamWages = List.of(wages(10, 2, 300, 200), wages(20, 1, 300, 300));
        when(humans.activePlayerWagesByTeam()).thenReturn(teamWages);
        when(humans.findTop100ByTypeIdAndRetiredFalseAndTeamIdIsNotNullOrderByWageDescIdAsc(1))
                .thenReturn(List.of(player(100, "Top Player", 20, 300)));
        when(finances.findAllBySeasonNumberAndCategory(7, "MATCH_DAY")).thenReturn(List.of(
                crowd(10, "Match day vs Beta (First League) - 10000 fans"),
                crowd(10, "Match day vs Beta (National Cup) - 20000 fans"),
                crowd(20, "Match day vs Alpha (National Cup) - 30000 fans"),
                crowd(20, "Other income - 90000 fans")));

        var ranking = service.ranking();

        assertEquals(7, ranking.season());
        assertEquals("National Cup", ranking.competitions().get(0).competitionName());
        assertEquals(25_000, ranking.competitions().get(0).averageAttendance());
        assertEquals(2, ranking.competitions().get(0).homeMatches());
        assertEquals(3, ranking.competitions().get(0).players());
        assertEquals(600, ranking.competitions().get(0).totalPlayerWages());
        assertEquals(200, ranking.competitions().get(0).averagePlayerWage());
        assertEquals(300, ranking.competitions().get(0).highestPlayerWage());
        assertEquals(30_000, ranking.clubs().get(0).totalAttendance());
        assertEquals("Top Player", ranking.players().get(0).playerName());
    }

    @Test
    void detailIncludesEliminatedClubAndEveryPlayerWithTheirClub() {
        when(gameState.currentSeason()).thenReturn(7);
        when(competitions.findById(2L)).thenReturn(Optional.of(competition(2, "National Cup")));
        when(entries.findAllByCompetitionIdAndSeasonNumber(2L, 7L)).thenReturn(List.of(entry(2, 10)));
        when(results.findAllByCompetitionIdAndSeasonNumber(2L, 7L)).thenReturn(List.of(result(2, 10, 20)));
        when(teams.findAllByCompetitionId(2L)).thenReturn(List.of());
        when(teams.findAllById(org.mockito.ArgumentMatchers.<Long>anySet()))
                .thenReturn(List.of(team(10, "Alpha", 1), team(20, "Beta", 1)));
        when(humans.findAllByTeamIdInAndTypeIdAndRetiredFalse(org.mockito.ArgumentMatchers.<Long>anySet(),
                org.mockito.ArgumentMatchers.eq(1L))).thenReturn(List.of(
                        player(100, "Alpha Star", 10, 200), player(200, "Beta Star", 20, 300)));
        when(finances.findAllBySeasonNumberAndCategory(7, "MATCH_DAY")).thenReturn(List.of(
                crowd(10, "Match day vs Beta (National Cup) - 12000 fans"),
                crowd(10, "Match day vs Beta (First League) - 9000 fans"),
                crowd(20, "Match day vs Alpha (National Cup) - 15000 fans")));

        var detail = service.detail(2L);

        assertEquals(2, detail.clubs().size());
        assertEquals(2, detail.players().size());
        assertEquals("Beta", detail.players().get(0).teamName());
        assertEquals(15_000, detail.clubs().get(0).totalAttendance());
        assertEquals(12_000, detail.clubs().get(1).totalAttendance());
        assertEquals(1, detail.clubs().get(1).players());
    }

    private static Competition competition(long id, String name) {
        Competition value = new Competition();
        value.setId(id); value.setName(name); value.setTypeId(id); return value;
    }
    private static Team team(long id, String name, long competitionId) {
        Team value = new Team();
        value.setId(id); value.setName(name); value.setCompetitionId(competitionId); return value;
    }
    private static CompetitionTeamInfo entry(long competitionId, long teamId) {
        CompetitionTeamInfo value = new CompetitionTeamInfo();
        value.setCompetitionId(competitionId); value.setTeamId(teamId); return value;
    }
    private static CompetitionTeamInfoDetail result(long competitionId, long team1, long team2) {
        CompetitionTeamInfoDetail value = new CompetitionTeamInfoDetail();
        value.setCompetitionId(competitionId); value.setTeam1Id(team1); value.setTeam2Id(team2); return value;
    }
    private static FinancialRecord crowd(long teamId, String description) {
        FinancialRecord value = new FinancialRecord();
        value.setTeamId(teamId); value.setDescription(description); return value;
    }
    private static HumanRepository.TeamPlayerWages wages(long teamId, long players, long total, long top) {
        var value = mock(HumanRepository.TeamPlayerWages.class);
        when(value.getTeamId()).thenReturn(teamId);
        when(value.getPlayerCount()).thenReturn(players);
        when(value.getTotalWage()).thenReturn(total);
        when(value.getTopWage()).thenReturn(top);
        return value;
    }
    private static Human player(long id, String name, long teamId, long wage) {
        Human value = new Human();
        value.setId(id); value.setName(name); value.setTeamId(teamId); value.setWage(wage); return value;
    }
}
