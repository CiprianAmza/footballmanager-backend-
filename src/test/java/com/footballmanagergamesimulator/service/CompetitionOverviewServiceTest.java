package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompetitionOverviewServiceTest {

    @Test
    void historicalOverviewUsesCapturedValuesInsteadOfCurrentSquad() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
        CompetitionHistoryRepository history = mock(CompetitionHistoryRepository.class);
        CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        CompetitionProgressService progress = mock(CompetitionProgressService.class);
        CompetitionOverviewService service = new CompetitionOverviewService(
                competitions, entries, history, results, teams, humans, progress);

        Competition competition = new Competition();
        competition.setId(7); competition.setTypeId(1); competition.setName("Historical League");
        Team team = new Team();
        team.setId(11); team.setName("Alpha"); team.setReputation(9999);
        Human currentPlayer = new Human();
        currentPlayer.setTeamId(11L); currentPlayer.setRating(999); currentPlayer.setTransferValue(999_000_000);

        CompetitionHistory snapshot = new CompetitionHistory();
        snapshot.setTeamId(11); snapshot.setCompetitionId(7); snapshot.setSeasonNumber(2);
        snapshot.setLastPosition(1); snapshot.setLandscapeSnapshotCaptured(true);
        snapshot.setTopElevenRating(205.5); snapshot.setSquadValue(150_000_000L);
        snapshot.setMonthlyPayroll(2_000_000L); snapshot.setAnnualPayroll(24_000_000L);
        snapshot.setReputation(7000); snapshot.setMediaPrediction(3); snapshot.setEntryRound(1L);

        when(competitions.findById(7L)).thenReturn(Optional.of(competition));
        when(entries.findAllByCompetitionIdAndSeasonNumber(7, 2)).thenReturn(List.of());
        when(history.findByCompetitionId(7)).thenReturn(List.of(snapshot));
        when(history.findAllByCompetitionIdAndSeasonNumber(7, 2)).thenReturn(List.of(snapshot));
        when(teams.findAllById(anySet())).thenReturn(List.of(team));
        when(teams.findById(11L)).thenReturn(Optional.of(team));
        when(humans.findAllByTeamIdInAndTypeId(anySet(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(currentPlayer));
        when(humans.findAll()).thenReturn(List.of(currentPlayer));
        when(progress.roundLabel(7, 1, 2)).thenReturn("League");
        when(progress.teamProgress(11, 7, 2)).thenReturn(Map.of());
        when(progress.stages(7, 2)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) service.overview(7, 2, null).get("teams")).get(0);

        assertEquals(205.5, row.get("topElevenRating"));
        assertEquals(150_000_000L, row.get("squadValue"));
        assertEquals(2_000_000L, row.get("monthlyPayroll"));
        assertEquals(7000, row.get("reputation"));
        assertEquals(3, row.get("mediaPrediction"));
        assertEquals("HISTORICAL_SNAPSHOT", row.get("landscapeDataSource"));
    }
}
