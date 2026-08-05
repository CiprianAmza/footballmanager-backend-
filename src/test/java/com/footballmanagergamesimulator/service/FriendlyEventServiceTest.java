package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.FriendlyEvent;
import com.footballmanagergamesimulator.model.FriendlyMatch;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyMatchRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FriendlyEventServiceTest {

    @Mock FriendlyEventRepository eventRepository;
    @Mock FriendlyMatchRepository matchRepository;
    @Mock TeamRepository teamRepository;
    @Mock CalendarEventRepository calendarEventRepository;
    @Mock GameCalendarRepository gameCalendarRepository;
    @Mock CalendarService calendarService;
    @Mock NationService nationService;
    @Mock FinanceService financeService;

    private FriendlyEventService service;
    private List<Team> teams;

    @BeforeEach
    void setUp() {
        service = new FriendlyEventService(eventRepository, matchRepository, teamRepository,
                calendarEventRepository, gameCalendarRepository, calendarService, nationService, financeService);
        teams = List.of(team(1, "Host"), team(2, "Alpha"), team(3, "Beta"), team(4, "Gamma"));
        for (Team team : teams) {
            when(teamRepository.findById(team.getId())).thenReturn(Optional.of(team));
        }
        when(teamRepository.findAllById(any())).thenReturn(teams);
        when(teamRepository.findNameById(1)).thenReturn("Host");
        when(nationService.infoFor(anyLong())).thenReturn(new NationService.NationInfo(2, "Dong", "do"));
        when(matchRepository.findAllByFriendlyEventIdOrderByDayAsc(anyLong())).thenReturn(List.of());
        AtomicLong calendarId = new AtomicLong(10);
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenAnswer(invocation -> {
            CalendarEvent event = invocation.getArgument(0);
            event.setId(calendarId.incrementAndGet());
            return event;
        });
        when(matchRepository.save(any(FriendlyMatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(FriendlyEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(4);
        calendar.setCurrentDay(5);
        when(gameCalendarRepository.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
        when(calendarService.getDateDisplay(anyInt())).thenAnswer(invocation -> "Date " + invocation.getArgument(0));
    }

    @Test
    void confirmingMiniCupPostsEconomyAndCreatesTwoSemiFinals() {
        FriendlyEvent event = miniCup();
        when(eventRepository.findById(44L)).thenReturn(Optional.of(event));

        Map<String, Object> result = service.confirm(44L);

        assertThat(result.get("status")).isEqualTo("CONFIRMED");
        assertThat(result.get("startDate")).isEqualTo("Date 10");
        verify(financeService, times(5)).recordExpense(anyLong(), anyInt(), anyInt(), any(), any(), anyLong());
        verify(financeService, times(3)).recordTransaction(anyLong(), anyInt(), anyInt(), any(), any(), anyLong());
        verify(matchRepository, times(2)).save(any(FriendlyMatch.class));
        verify(calendarEventRepository, times(2)).save(any(CalendarEvent.class));
    }

    @Test
    void miniCupDraftRejectsMissingInviteesBeforeAnyMoneyMoves() {
        Map<String, Object> request = Map.of(
                "organizerTeamId", 1,
                "season", 4,
                "eventType", "MINI_CUP",
                "startDay", 10,
                "endDay", 14,
                "participantTeamIds", List.of(2, 3));

        assertThatThrownBy(() -> service.createDraft(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 4 teams");
    }

    @Test
    void draftRejectsPastDayAndPreviousSeason() {
        Map<String, Object> pastDay = Map.of(
                "organizerTeamId", 1,
                "season", 4,
                "eventType", "TRAINING_CAMP",
                "startDay", 5,
                "endDay", 10);
        Map<String, Object> previousSeason = Map.of(
                "organizerTeamId", 1,
                "season", 3,
                "eventType", "TRAINING_CAMP",
                "startDay", 10,
                "endDay", 14);

        assertThatThrownBy(() -> service.createDraft(pastDay))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after the current day");
        assertThatThrownBy(() -> service.createDraft(previousSeason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Season 4 or Season 5");
    }

    @Test
    void draftAllowsNextSeasonPlanningFromItsPreSeason() {
        Map<String, Object> nextSeason = Map.of(
                "organizerTeamId", 1,
                "season", 5,
                "eventType", "TRAINING_CAMP",
                "startDay", 1,
                "endDay", 7);

        Map<String, Object> result = service.createDraft(nextSeason);

        assertThat(result.get("season")).isEqualTo(5);
        assertThat(result.get("startDate")).isEqualTo("Date 1");
    }

    @Test
    void tournamentCreatesPersistentTraditionAndNextEditionKeepsItsIdentity() {
        Map<String, Object> inauguralRequest = Map.of(
                "organizerTeamId", 1,
                "season", 4,
                "name", "Host Summer Cup",
                "eventType", "MINI_CUP",
                "startDay", 10,
                "endDay", 14,
                "participantTeamIds", List.of(2, 3, 4));

        Map<String, Object> inaugural = service.createDraft(inauguralRequest);
        String seriesId = String.valueOf(inaugural.get("seriesId"));
        assertThat(seriesId).isNotBlank();
        assertThat(inaugural.get("editionNumber")).isEqualTo(1);

        FriendlyEvent firstEdition = miniCup();
        firstEdition.setSeriesId(seriesId);
        firstEdition.setSeriesName("Host Summer Cup");
        firstEdition.setEditionNumber(1);
        firstEdition.setStatus("COMPLETED");
        when(eventRepository.findAllBySeriesIdOrderBySeasonAscEditionNumberAsc(seriesId))
                .thenReturn(List.of(firstEdition));
        Map<String, Object> nextEditionRequest = Map.of(
                "organizerTeamId", 1,
                "season", 5,
                "name", "Host Summer Cup",
                "eventType", "MINI_CUP",
                "startDay", 1,
                "endDay", 5,
                "participantTeamIds", List.of(2, 3, 4));

        Map<String, Object> nextEdition = service.proposeEdition(seriesId, nextEditionRequest);

        assertThat(nextEdition.get("seriesId")).isEqualTo(seriesId);
        assertThat(nextEdition.get("seriesName")).isEqualTo("Host Summer Cup");
        assertThat(nextEdition.get("editionNumber")).isEqualTo(2);
    }

    @Test
    void confirmationRevalidatesDraftDateBeforePostingMoney() {
        FriendlyEvent event = miniCup();
        event.setStartDay(5);
        when(eventRepository.findById(44L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.confirm(44L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after the current day");
        verify(financeService, times(0)).recordExpense(anyLong(), anyInt(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void exposesCompletedFriendlyTournamentWinsAsSeparateHonours() {
        FriendlyEvent wonCup = miniCup();
        wonCup.setStatus("COMPLETED");
        wonCup.setWinnerTeamId(1L);
        FriendlyEvent cancelled = miniCup();
        cancelled.setId(45);
        cancelled.setStatus("CANCELLED");
        cancelled.setWinnerTeamId(1L);
        when(eventRepository.findAllByWinnerTeamIdOrderBySeasonDesc(1)).thenReturn(List.of(wonCup, cancelled));

        Map<String, Object> result = service.getFriendlyHonours(1);

        assertThat(result.get("total")).isEqualTo(1);
        assertThat(result.get("miniCups")).isEqualTo(1L);
        assertThat((List<?>) result.get("honours")).hasSize(1);
    }

    private FriendlyEvent miniCup() {
        FriendlyEvent event = new FriendlyEvent();
        event.setId(44);
        event.setSeason(4);
        event.setOrganizerTeamId(1);
        event.setName("Host Challenge Cup");
        event.setEventType("MINI_CUP");
        event.setStatus("DRAFT");
        event.setHostNationId(2);
        event.setLocationName("Dong National Stadium");
        event.setStartDay(10);
        event.setEndDay(14);
        event.setFocus("TACTICAL");
        event.setFormat("KNOCKOUT");
        event.setParticipantTeamIds("1,2,3,4");
        event.setParticipationFee(100_000);
        event.setPrizePool(1_000_000);
        event.setOrganizerCost(500_000);
        return event;
    }

    private Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setTotalFinances(20_000_000);
        return team;
    }
}
