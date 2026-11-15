package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FixtureSchedulingExpansionTest {
    @Test
    void addsOnlyNewLeagueEventsAndKeepsExistingEuropeanEventsUnchanged() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CalendarEventRepository calendars = mock(CalendarEventRepository.class);
        CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
        FixtureSchedulingService service = new FixtureSchedulingService();
        ReflectionTestUtils.setField(service, "competitionRepository", competitions);
        ReflectionTestUtils.setField(service, "calendarEventRepository", calendars);
        ReflectionTestUtils.setField(service, "competitionTeamInfoMatchRepository", fixtures);
        Competition league = new Competition(); league.setId(18); league.setTypeId(1); league.setName("Liga Veyra");
        when(competitions.findById(18L)).thenReturn(Optional.of(league));
        when(fixtures.findDistinctRoundsByCompetitionIdAndSeasonNumber(18L, "2"))
                .thenReturn(LongStream.rangeClosed(1, 44).boxed().toList());
        CalendarEvent european = new CalendarEvent(); european.setCompetitionId(10L);
        european.setEventType("MATCH_EUROPEAN"); european.setDay(28); european.setStatus("PENDING");
        when(calendars.findAllBySeason(2)).thenReturn(List.of(european));

        service.addDomesticCompetitionCalendar(18, 2);

        @SuppressWarnings("unchecked") ArgumentCaptor<List<CalendarEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(calendars).saveAll(captor.capture());
        var events = captor.getValue();
        assertEquals(88, events.size());
        assertEquals(44, events.stream().filter(e -> "MATCH_LEAGUE".equals(e.getEventType())).count());
        assertEquals(44, events.stream().filter(e -> "PRESS_CONFERENCE".equals(e.getEventType())).count());
        assertTrue(events.stream().allMatch(e -> e.getCompetitionId() == 18 && e.getSeason() == 2));
        assertEquals(28, european.getDay());
        assertEquals("PENDING", european.getStatus());
        verify(calendars, never()).deleteAll();

        when(calendars.findAllBySeason(2)).thenReturn(events);
        clearInvocations(calendars, fixtures);
        service.addDomesticCompetitionCalendar(18, 2);
        verify(calendars, never()).saveAll(any());
        verifyNoInteractions(fixtures);
    }

    @Test
    void rejectsEuropeanCompetitionRatherThanAccidentallyReschedulingIt() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        FixtureSchedulingService service = new FixtureSchedulingService();
        ReflectionTestUtils.setField(service, "competitionRepository", competitions);
        Competition european = new Competition(); european.setId(10); european.setTypeId(4);
        when(competitions.findById(10L)).thenReturn(Optional.of(european));
        assertThrows(IllegalArgumentException.class, () -> service.addDomesticCompetitionCalendar(10, 1));
    }
}
