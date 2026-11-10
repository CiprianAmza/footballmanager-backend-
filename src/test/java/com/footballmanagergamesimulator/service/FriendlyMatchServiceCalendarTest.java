package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.FriendlyMatch;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyMatchRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FriendlyMatchServiceCalendarTest {
    private final FriendlyMatchService service = new FriendlyMatchService();
    private final FriendlyMatchRepository matches = mock(FriendlyMatchRepository.class);
    private final CalendarEventRepository events = mock(CalendarEventRepository.class);
    private final GameCalendarRepository calendars = mock(GameCalendarRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final UserContext users = mock(UserContext.class);
    private final GameLock lock = new GameLock();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "friendlyMatchRepository", matches);
        ReflectionTestUtils.setField(service, "calendarEventRepository", events);
        ReflectionTestUtils.setField(service, "gameCalendarRepository", calendars);
        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "userContext", users);
        ReflectionTestUtils.setField(service, "gameLock", lock);
    }

    @Test
    void automaticFriendliesUseTheDaysThatHaveCalendarTriggers() {
        Team home = team(1, 1);
        Team away = team(2, 2);
        when(users.getAllHumanTeamIds()).thenReturn(List.of(1L));
        when(teams.findAll()).thenReturn(List.of(home, away, team(3, 2), team(4, 2), team(5, 2)));
        when(teams.findById(1L)).thenReturn(Optional.of(home));
        when(teams.findNameById(1L)).thenReturn("Home");

        service.autoSchedulePreSeasonFriendlies(1, Set.of(7, 14, 21, 28));

        var saved = org.mockito.ArgumentCaptor.forClass(FriendlyMatch.class);
        verify(matches, times(4)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(FriendlyMatch::getDay)
                .containsExactly(7, 14, 21, 28);
    }

    @Test
    void restoresMissingTriggerForAnExistingScheduledFriendly() {
        FriendlyMatch match = new FriendlyMatch();
        match.setDay(8);
        when(matches.findAllBySeasonAndDayAndStatus(1, 8, "SCHEDULED")).thenReturn(List.of(match));
        when(events.findAllBySeasonAndDayAndStatus(1, 8, "PENDING")).thenReturn(List.of());

        service.ensureCalendarEventForDay(1, 8);

        var saved = org.mockito.ArgumentCaptor.forClass(CalendarEvent.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("MATCH_FRIENDLY");
        assertThat(saved.getValue().getDay()).isEqualTo(8);
        assertThat(saved.getValue().getPhase()).isEqualTo("EVENING");
    }

    @Test
    void doesNotDuplicateAnExistingFriendlyTrigger() {
        FriendlyMatch match = new FriendlyMatch();
        CalendarEvent trigger = new CalendarEvent();
        trigger.setEventType("MATCH_FRIENDLY");
        when(matches.findAllBySeasonAndDayAndStatus(1, 7, "SCHEDULED")).thenReturn(List.of(match));
        when(events.findAllBySeasonAndDayAndStatus(1, 7, "PENDING")).thenReturn(List.of(trigger));

        service.ensureCalendarEventForDay(1, 7);

        verify(events, never()).save(any());
    }

    @Test
    void rejectsSchedulingInThePast() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(1);
        calendar.setCurrentDay(20);
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));

        assertThat(service.scheduleFriendly(1, 2, 14, 1))
                .containsEntry("success", false)
                .containsEntry("error", "Friendlies must be scheduled for a future day");
        verify(matches, never()).save(any());
    }

    @Test
    void recoveryLeavesCurrentAndTournamentFriendliesAlone() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(2);
        calendar.setCurrentDay(20);
        FriendlyMatch today = new FriendlyMatch();
        today.setDay(20);
        FriendlyMatch tournament = new FriendlyMatch();
        tournament.setDay(8);
        tournament.setFriendlyEventId(5L);
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
        when(matches.findAllBySeasonAndStatus(2, "SCHEDULED")).thenReturn(List.of(today, tournament));

        assertThat(service.recoverMissedStandaloneFriendlies()).containsEntry("recovered", 0);
        verify(matches, never()).save(any());
    }

    private static Team team(long id, long competitionId) {
        Team team = new Team();
        team.setId(id);
        team.setName("Team " + id);
        team.setCompetitionId(competitionId);
        return team;
    }
}
