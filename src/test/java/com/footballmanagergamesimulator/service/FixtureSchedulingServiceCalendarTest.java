package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixtureSchedulingServiceCalendarTest {

    @Test
    void mapsLocMatchdayToRoundAndKeepsSeparateLegDates() {
        FixtureSchedulingService service = new FixtureSchedulingService();
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        CompetitionTeamInfoMatchRepository matchRepository = mock(CompetitionTeamInfoMatchRepository.class);
        CalendarEventRepository calendarEventRepository = mock(CalendarEventRepository.class);

        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoMatchRepository", matchRepository);
        ReflectionTestUtils.setField(service, "calendarEventRepository", calendarEventRepository);
        ReflectionTestUtils.setField(service, "competitionFormat", new CompetitionFormatConfig());

        Competition loc = new Competition();
        loc.setId(40L);
        loc.setTypeId(4);
        when(competitionRepository.findById(40L)).thenReturn(Optional.of(loc));

        CalendarEvent leg1Event = europeanEvent(2, 40L, 9, 100, 1);
        CalendarEvent leg2Event = europeanEvent(2, 40L, 9, 107, 2);
        when(calendarEventRepository.findBySeasonAndCompetitionIdAndMatchday(2, 40L, 9))
                .thenReturn(List.of(leg1Event, leg2Event));

        CompetitionTeamInfoMatch leg1 = match(1);
        CompetitionTeamInfoMatch leg2 = match(2);
        // LoC matchday 9 maps to round 8.
        when(matchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(40L, 8, "2"))
                .thenReturn(List.of(leg1, leg2));

        service.assignMatchDayForNewRound(40L, 9, 2);

        assertEquals(100, leg1.getDay());
        assertEquals(107, leg2.getDay());
        verify(matchRepository).saveAll(anyList());
    }

    private CalendarEvent europeanEvent(int season, long competitionId, int matchday,
                                         int day, int leg) {
        CalendarEvent event = new CalendarEvent();
        event.setSeason(season);
        event.setCompetitionId(competitionId);
        event.setMatchday(matchday);
        event.setDay(day);
        event.setLegNumber(leg);
        event.setEventType("MATCH_EUROPEAN");
        return event;
    }

    private CompetitionTeamInfoMatch match(int leg) {
        CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
        match.setLegNumber(leg);
        return match;
    }
}
