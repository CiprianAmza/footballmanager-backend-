package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.CalendarEntryView;
import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.FriendlyEvent;
import com.footballmanagergamesimulator.model.FriendlyMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.FriendlyEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyMatchRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchServiceFriendlyScheduleTest {

    private MatchService service;
    private FriendlyMatchRepository friendlyMatches;
    private FriendlyEventRepository friendlyEvents;

    @BeforeEach
    void setUp() {
        service = new MatchService();
        service.teamRepository = mock(TeamRepository.class);
        service.competitionRepository = mock(CompetitionRepository.class);
        service.competitionTeamInfoDetailRepository = mock(CompetitionTeamInfoDetailRepository.class);
        service.calendarService = mock(CalendarService.class);
        friendlyMatches = mock(FriendlyMatchRepository.class);
        friendlyEvents = mock(FriendlyEventRepository.class);
        service.friendlyMatchRepository = friendlyMatches;
        service.friendlyEventRepository = friendlyEvents;
        when(service.calendarService.getDateDisplay(10)).thenReturn("10 August");
    }

    @Test
    void tournamentMatchAppearsInScheduleAndCalendarForInvitedClub() {
        FriendlyMatch match = new FriendlyMatch();
        match.setId(7);
        match.setSeason(4);
        match.setDay(10);
        match.setHomeTeamId(1);
        match.setAwayTeamId(2);
        match.setHomeTeamName("Host");
        match.setAwayTeamName("Guest");
        match.setStatus("COMPLETED");
        match.setHomeGoals(2);
        match.setAwayGoals(1);
        match.setFriendlyEventId(44L);
        match.setEventStage("SEMI_FINAL");

        FriendlyEvent event = new FriendlyEvent();
        event.setId(44);
        event.setName("Summer Challenge Cup");
        when(friendlyMatches.findAllBySeasonAndHomeTeamIdOrSeasonAndAwayTeamId(4, 2, 4, 2))
                .thenReturn(List.of(match));
        when(friendlyEvents.findAllById(List.of(44L))).thenReturn(List.of(event));

        List<ScheduleView> schedule = service.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List.of(), 2, 4);
        List<CalendarEntryView> calendar = service.getCalendarEntries(List.of(), 2, 4);

        assertThat(schedule).singleElement().satisfies(view -> {
            assertThat(view.getCompetitionName()).isEqualTo("Summer Challenge Cup · SEMI FINAL");
            assertThat(view.getOpponentTeam()).isEqualTo("Host");
            assertThat(view.getScore()).isEqualTo("1 - 2");
            assertThat(view.getWinnerTeamId()).isEqualTo(1L);
        });
        assertThat(calendar).singleElement().satisfies(entry -> {
            assertThat(entry.getCompetitionType()).isEqualTo("Friendly");
            assertThat(entry.getStatus()).isEqualTo("played");
            assertThat(entry.getResultOutcome()).isEqualTo("L");
        });
    }
}
