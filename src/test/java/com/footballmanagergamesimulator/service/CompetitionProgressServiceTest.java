package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompetitionProgressServiceTest {

    @Test
    void backendNamesRoundTwoQuarterFinalInFourRoundCup() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
        CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
        CompetitionProgressService service = new CompetitionProgressService(
                competitions, mock(CompetitionTeamInfoRepository.class), fixtures, results,
                mock(TeamCompetitionDetailRepository.class), mock(TeamRepository.class),
                new CompetitionFormatConfig(), mock(CalendarEventRepository.class),
                mock(GameCalendarRepository.class), mock(CalendarService.class));
        Competition cup = new Competition();
        cup.setId(9); cup.setTypeId(2);
        when(competitions.findById(9L)).thenReturn(Optional.of(cup));
        when(fixtures.findDistinctRoundsByCompetitionIdAndSeasonNumber(9, "2"))
                .thenReturn(List.of(1L, 2L, 3L, 4L));
        when(results.findAllByCompetitionIdAndSeasonNumber(9, 2)).thenReturn(List.of());

        assertEquals("Quarter-Final", service.roundLabel(9, 2, 2));
        assertEquals("Semi-Final", service.roundLabel(9, 3, 2));
        assertEquals("Final", service.roundLabel(9, 4, 2));
    }

    @Test
    void leagueOfChampionsLabelsAreOrderedAndBackendOwned() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionProgressService service = new CompetitionProgressService(
                competitions, mock(CompetitionTeamInfoRepository.class),
                mock(CompetitionTeamInfoMatchRepository.class), mock(CompetitionTeamInfoDetailRepository.class),
                mock(TeamCompetitionDetailRepository.class), mock(TeamRepository.class),
                new CompetitionFormatConfig(), mock(CalendarEventRepository.class),
                mock(GameCalendarRepository.class), mock(CalendarService.class));
        Competition loc = new Competition();
        loc.setId(10); loc.setTypeId(4);
        when(competitions.findById(10L)).thenReturn(Optional.of(loc));

        assertEquals("Qualifying Round 1", service.roundLabel(10, 0, 1));
        assertEquals("Qualifying Round 2", service.roundLabel(10, 1, 1));
        assertEquals("Group Stage · Matchday 1", service.roundLabel(10, 2, 1));
        assertEquals("Quarter-Final", service.roundLabel(10, 8, 1));
    }

    @Test
    void directGroupEntrantIsNotEliminatedBeforeTheGroupStageStarts() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
        CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        CalendarEventRepository events = mock(CalendarEventRepository.class);
        GameCalendarRepository calendars = mock(GameCalendarRepository.class);
        CompetitionProgressService service = new CompetitionProgressService(
                competitions, entries, fixtures, results, mock(TeamCompetitionDetailRepository.class),
                teams, new CompetitionFormatConfig(), events, calendars, mock(CalendarService.class));

        Competition loc = new Competition();
        loc.setId(10); loc.setTypeId(4); loc.setName("League of Champions");
        CompetitionTeamInfo directEntry = new CompetitionTeamInfo();
        directEntry.setCompetitionId(10); directEntry.setTeamId(86);
        directEntry.setSeasonNumber(4); directEntry.setRound(2); directEntry.setGroupNumber(1);
        CompetitionTeamInfo team2 = groupEntry(2);
        CompetitionTeamInfo team54 = groupEntry(54);
        CompetitionTeamInfo team15 = groupEntry(15);
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(4); calendar.setCurrentDay(1);
        CalendarEvent firstGroupMatchday = new CalendarEvent();
        firstGroupMatchday.setSeason(4); firstGroupMatchday.setCompetitionId(10L);
        firstGroupMatchday.setMatchday(3); firstGroupMatchday.setEventType("MATCH_EUROPEAN");
        firstGroupMatchday.setStatus("PENDING"); firstGroupMatchday.setDay(120);

        when(competitions.findById(10L)).thenReturn(Optional.of(loc));
        when(entries.findAllByTeamIdAndSeasonNumber(86, 4)).thenReturn(List.of(directEntry));
        when(entries.findAllByCompetitionIdAndSeasonNumber(10, 4))
                .thenReturn(List.of(directEntry, team2, team54, team15));
        when(fixtures.findAllBySeasonNumberAndTeamId("4", 86)).thenReturn(List.of());
        // A legacy/imported save can contain a complete-looking table tagged as
        // season 4 even though the season-4 calendar is still before qualifying.
        when(results.findAllByCompetitionIdAndSeasonNumber(10, 4)).thenReturn(List.of(
                result(2, 86, 2, "0 - 1"), result(2, 54, 15, "2 - 0"),
                result(3, 86, 54, "0 - 1"), result(3, 15, 2, "0 - 1"),
                result(4, 86, 15, "0 - 1"), result(4, 2, 54, "1 - 0"),
                result(5, 2, 86, "1 - 0"), result(5, 15, 54, "1 - 0"),
                result(6, 54, 86, "1 - 0"), result(6, 2, 15, "1 - 0"),
                result(7, 15, 86, "1 - 0"), result(7, 54, 2, "1 - 0")));
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
        when(events.findAllBySeason(4)).thenReturn(List.of(firstGroupMatchday));
        when(teams.findAllById(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        Map<String, Object> progress = service.teamProgress(86, 10, 4);

        assertEquals("QUALIFIED_FOR_STAGE", progress.get("status"));
        assertEquals("Qualified for Group Stage", progress.get("statusLabel"));
        assertEquals("Group Stage", progress.get("stageReached"));
    }

    private static CompetitionTeamInfo groupEntry(long teamId) {
        CompetitionTeamInfo entry = new CompetitionTeamInfo();
        entry.setCompetitionId(10); entry.setTeamId(teamId);
        entry.setSeasonNumber(4); entry.setRound(2); entry.setGroupNumber(1);
        return entry;
    }

    private static CompetitionTeamInfoDetail result(long round, long home, long away, String score) {
        CompetitionTeamInfoDetail detail = new CompetitionTeamInfoDetail();
        detail.setCompetitionId(10); detail.setSeasonNumber(4); detail.setRoundId(round);
        detail.setTeam1Id(home); detail.setTeam2Id(away); detail.setScore(score);
        return detail;
    }
}
