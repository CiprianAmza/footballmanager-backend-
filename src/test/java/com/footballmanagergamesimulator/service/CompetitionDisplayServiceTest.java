package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompetitionDisplayServiceTest {
    @Mock private CompetitionTeamInfoRepository participants;
    @Mock private CompetitionHistoryRepository history;
    @Mock private TeamCompetitionDetailRepository details;
    @Mock private TeamRepository teams;
    @Mock private RoundRepository rounds;
    @InjectMocks private CompetitionDisplayService service;

    @Test
    void currentStandingsReadOnlySelectedSeasonAndBatchTeamData() {
        Round round = new Round();
        round.setSeason(13);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        CompetitionTeamInfo first = participant(10, 14, 13);
        CompetitionTeamInfo second = participant(20, 14, 13);
        when(participants.findAllByCompetitionIdAndSeasonNumber(14, 13))
                .thenReturn(List.of(first, second));
        when(teams.findAllById(List.of(10L, 20L))).thenReturn(List.of(team(10), team(20)));
        when(details.findAllByCompetitionIdAndTeamIdIn(14, List.of(10L, 20L)))
                .thenReturn(List.of(detail(10, 14, 3), detail(20, 14, 8)));

        var standings = service.getTeamDetails(14, 20L);

        assertEquals(List.of(20L, 10L), standings.stream().map(row -> row.getTeamId()).toList());
        assertEquals(List.of(1, 2), standings.stream().map(row -> row.getPosition()).toList());
        assertTrue(standings.get(0).isHumanTeam());
        verify(participants, never()).findAll();
        verify(details, never()).findFirstByTeamIdAndCompetitionId(any(Long.class), any(Long.class));
        verify(teams, never()).findById(any(Long.class));
    }

    @Test
    void historicalStandingsReadOnlyRequestedCompetitionAndSeason() {
        CompetitionHistory row = new CompetitionHistory();
        row.setCompetitionId(14);
        row.setSeasonNumber(4);
        row.setTeamId(10);
        row.setPoints(52);
        when(history.findAllByCompetitionIdAndSeasonNumber(14, 4)).thenReturn(List.of(row));
        when(teams.findAllById(any())).thenReturn(List.of(team(10)));

        var standings = service.getHistoricalTeamDetails(14, 4);

        assertEquals(1, standings.size());
        assertEquals("52", standings.get(0).getPoints());
        verify(history, never()).findAll();
        verify(teams, never()).findById(any(Long.class));
    }

    private static CompetitionTeamInfo participant(long teamId, long competitionId, long season) {
        CompetitionTeamInfo row = new CompetitionTeamInfo();
        row.setTeamId(teamId);
        row.setCompetitionId(competitionId);
        row.setSeasonNumber(season);
        return row;
    }

    private static Team team(long id) {
        Team team = new Team();
        team.setId(id);
        team.setName("Team " + id);
        return team;
    }

    private static TeamCompetitionDetail detail(long teamId, long competitionId, int points) {
        TeamCompetitionDetail row = new TeamCompetitionDetail();
        row.setTeamId(teamId);
        row.setCompetitionId(competitionId);
        row.setPoints(points);
        return row;
    }
}
