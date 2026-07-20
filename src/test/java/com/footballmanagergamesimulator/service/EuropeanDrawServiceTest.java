package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EuropeanDrawServiceTest {

    private final CalendarEventRepository calendarEvents = mock(CalendarEventRepository.class);
    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
    private final CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
    private final GameCalendarRepository calendars = mock(GameCalendarRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final CompetitionProgressService progress = mock(CompetitionProgressService.class);
    private final EuropeanCoefficientService coefficients = mock(EuropeanCoefficientService.class);
    private final EuropeanFixturePreparationService preparation = mock(EuropeanFixturePreparationService.class);
    private EuropeanDrawService service;

    @BeforeEach
    void setUp() {
        service = new EuropeanDrawService(calendarEvents, competitions, entries, fixtures,
                calendars, teams, new CompetitionFormatConfig(), progress, coefficients,
                preparation, new CalendarService());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesSeededPotsWithCoefficientsBeforeTheScheduledDraw() {
        long competitionId = 40L;
        Competition loc = new Competition();
        loc.setId(competitionId);
        loc.setTypeId(4);
        loc.setName("League of Champions");
        when(competitions.findById(competitionId)).thenReturn(Optional.of(loc));
        when(progress.roundLabel(competitionId, 0, 2)).thenReturn("Qualifying Round 1");

        CalendarEvent draw = event("EUROPEAN_DRAW", competitionId, 2, 1, 21);
        CalendarEvent match = event("MATCH_EUROPEAN", competitionId, 2, 1, 28);
        when(calendarEvents.findAllBySeason(2)).thenReturn(List.of(draw, match));
        when(calendarEvents.findBySeasonAndCompetitionIdAndMatchday(2, competitionId, 1))
                .thenReturn(List.of(draw, match));
        when(fixtures.findAllByCompetitionIdAndRoundAndSeasonNumber(competitionId, 0, "2"))
                .thenReturn(List.of());

        CompetitionTeamInfo first = entry(competitionId, 1L);
        CompetitionTeamInfo second = entry(competitionId, 2L);
        when(entries.findAllByRoundAndCompetitionIdAndSeasonNumber(0, competitionId, 2))
                .thenReturn(List.of(first, second));
        Team stronger = team(1L, "Seeded FC", 8000);
        Team weaker = team(2L, "Unseeded FC", 7000);
        when(teams.findAllById(List.of(1L, 2L))).thenReturn(List.of(stronger, weaker));
        when(coefficients.getClubCoefficientRolling(1L, 1)).thenReturn(18.75);
        when(coefficients.getClubCoefficientRolling(2L, 1)).thenReturn(4.25);
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(2);
        calendar.setCurrentDay(14);
        when(calendars.findBySeason(2)).thenReturn(List.of(calendar));

        List<Map<String, Object>> states = service.drawStates(competitionId, 2);

        assertEquals(1, states.size());
        assertEquals("POTS_PUBLISHED", states.get(0).get("status"));
        assertEquals(7, states.get(0).get("daysUntilDraw"));
        List<Map<String, Object>> pots = (List<Map<String, Object>>) states.get(0).get("pots");
        assertEquals(2, pots.size());
        List<Map<String, Object>> firstPot = (List<Map<String, Object>>) pots.get(0).get("teams");
        assertEquals("Seeded FC", firstPot.get(0).get("teamName"));
        assertEquals(18.75, firstPot.get(0).get("coefficient"));
        verify(preparation, never()).prepareMatchday(competitionId, 1, 2);
    }

    private CalendarEvent event(String type, long competitionId, int season, int matchday, int day) {
        CalendarEvent event = new CalendarEvent();
        event.setEventType(type);
        event.setCompetitionId(competitionId);
        event.setSeason(season);
        event.setMatchday(matchday);
        event.setDay(day);
        event.setStatus("PENDING");
        return event;
    }

    private CompetitionTeamInfo entry(long competitionId, long teamId) {
        CompetitionTeamInfo entry = new CompetitionTeamInfo();
        entry.setCompetitionId(competitionId);
        entry.setSeasonNumber(2);
        entry.setRound(0);
        entry.setTeamId(teamId);
        return entry;
    }

    private Team team(long id, String name, int reputation) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setReputation(reputation);
        return team;
    }
}
