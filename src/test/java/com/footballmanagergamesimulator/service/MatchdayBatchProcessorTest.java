package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.AdminController;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.economy.ClubCapTableService;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class MatchdayBatchProcessorTest {

    @InjectMocks private MatchdayBatchProcessor processor;
    @Mock private ManagerCareerService managerCareerService;
    @Mock private AdminController adminController;

    @Test
    void independentCompetitionsFillTheBoundedWorkerWaves() {
        List<MatchdayBatchProcessor.SimulationPlan> plans = List.of(
                plan(1, Set.of(11L, 12L), false),
                plan(2, Set.of(21L, 22L), false),
                plan(3, Set.of(31L, 32L), false));

        List<List<MatchdayBatchProcessor.SimulationPlan>> waves =
                MatchdayBatchProcessor.buildExecutionWaves(plans, 2);

        assertEquals(List.of(2, 1), waves.stream().map(List::size).toList());
        assertEquals(Set.of(1L, 2L, 3L), competitionIds(waves));
    }

    @Test
    void competitionsSharingATeamNeverRunInTheSameWave() {
        List<MatchdayBatchProcessor.SimulationPlan> plans = List.of(
                plan(1, Set.of(11L, 12L), false),
                plan(2, Set.of(12L, 21L), false),
                plan(3, Set.of(31L, 32L), false));

        List<List<MatchdayBatchProcessor.SimulationPlan>> waves =
                MatchdayBatchProcessor.buildExecutionWaves(plans, 4);

        assertEquals(2, waves.size());
        assertTrue(waves.get(0).stream().anyMatch(p -> p.event().getCompetitionId() == 1));
        assertFalse(waves.get(0).stream().anyMatch(p -> p.event().getCompetitionId() == 2));
        assertEquals(Set.of(1L, 2L, 3L), competitionIds(waves));
    }

    @Test
    void europeanCompetitionsUseOneLaneEvenWithDisjointTeams() {
        List<MatchdayBatchProcessor.SimulationPlan> plans = List.of(
                plan(4, Set.of(41L, 42L), true),
                plan(5, Set.of(51L, 52L), true),
                plan(1, Set.of(11L, 12L), false));

        List<List<MatchdayBatchProcessor.SimulationPlan>> waves =
                MatchdayBatchProcessor.buildExecutionWaves(plans, 4);

        assertEquals(2, waves.size());
        assertTrue(waves.stream().allMatch(wave ->
                wave.stream().filter(MatchdayBatchProcessor.SimulationPlan::european).count() <= 1));
        assertEquals(Set.of(1L, 4L, 5L), competitionIds(waves));
    }

    @Test
    void managerReviewsRunOnlyForLeaguesThatPlayed() {
        when(adminController.areJobOffersEnabled()).thenReturn(true);
        List<MatchdayBatchProcessor.SimulationPlan> plans = List.of(
                plan(1, Set.of(11L, 12L), false),
                new MatchdayBatchProcessor.SimulationPlan(event(2), Set.of(21L, 22L), 2),
                new MatchdayBatchProcessor.SimulationPlan(event(4), Set.of(41L, 42L), 4));

        processor.evaluateManagersAfterLeagueMatchdays(plans, 7);

        verify(managerCareerService).evaluateMidSeasonSackings(7, Set.of(1L));
    }

    @Test
    void controlledHumanManagerClubProducesOneProfileScopedNotificationOnRetry() {
        MatchdayBatchProcessor subject = new MatchdayBatchProcessor();
        MatchSimulationOrchestrator simulation = mock(MatchSimulationOrchestrator.class);
        UserContext users = mock(UserContext.class);
        ManagerInboxRepository messages = mock(ManagerInboxRepository.class);
        ClubCapTableService capTables = mock(ClubCapTableService.class);
        PersonProfileRepository profiles = mock(PersonProfileRepository.class);
        PersonProfile chairman = new PersonProfile();
        chairman.setId(99L);
        chairman.setCareerType(CareerType.CHAIRMAN);
        when(users.getAllHumanTeamIds()).thenReturn(List.of(10L));
        when(simulation.getAllMatchResults(7L, 3, 2)).thenReturn(List.of(Map.of(
                "competitionName", "Premier",
                "competitionId", 7L,
                "fixtureId", 701L,
                "team1Id", 10L,
                "team2Id", 20L,
                "team1Name", "Human FC",
                "team2Name", "Opponent FC",
                "score", "2 - 1")));
        ClubCapTableService.Holding controlling =
                new ClubCapTableService.Holding(1L, 99L, "Chairman", false, 100L, 0L, true);
        ClubCapTableService.CapTable table = new ClubCapTableService.CapTable(
                10L, 1L, 100L, 0L, 5001, 1L, 1L, List.of(controlling));
        when(capTables.viewBatch(any())).thenReturn(Map.of(10L, table));
        when(profiles.findAllById(any())).thenReturn(List.of(chairman));
        when(profiles.findByIdForUpdate(99L)).thenReturn(java.util.Optional.of(chairman));

        Set<String> keys = new HashSet<>();
        when(messages.existsByRecipientProfileIdAndDeduplicationKey(any(), anyString()))
                .thenAnswer(invocation -> !keys.add(invocation.getArgument(1)));
        when(messages.save(any(ManagerInbox.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(subject, "matchSimulationOrchestrator", simulation);
        ReflectionTestUtils.setField(subject, "userContext", users);
        ReflectionTestUtils.setField(subject, "managerInboxRepository", messages);
        ReflectionTestUtils.setField(subject, "clubCapTableService", capTables);
        ReflectionTestUtils.setField(subject, "personProfileRepository", profiles);
        ReflectionTestUtils.setField(subject, "chairmanInbox",
                new ChairmanInboxNotificationService(messages, profiles));

        CalendarEvent event = new CalendarEvent();
        event.setCompetitionId(7L);
        event.setMatchday(3);
        event.setSeason(2);
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(2);
        calendar.setCurrentDay(3);

        ReflectionTestUtils.invokeMethod(subject, "generateMatchDayNews", List.of(event), calendar);
        ReflectionTestUtils.invokeMethod(subject, "generateMatchDayNews", List.of(event), calendar);

        var saved = org.mockito.ArgumentCaptor.forClass(ManagerInbox.class);
        verify(messages, times(1)).save(saved.capture());
        assertThat(saved.getValue().getRecipientProfileId()).isEqualTo(99L);
        assertThat(saved.getValue().getTeamId()).isEqualTo(10L);
        assertThat(saved.getValue().getDeduplicationKey())
                .isEqualTo("MATCH_RESULT:7:2:3:701:99");
        assertThat(saved.getValue().getContent()).isEqualTo("Human FC 2 - 1 Opponent FC.");
        verify(messages, never()).save(argThat(message -> "league_news".equals(message.getCategory())));
    }

    private MatchdayBatchProcessor.SimulationPlan plan(long competitionId, Set<Long> teams, boolean european) {
        return new MatchdayBatchProcessor.SimulationPlan(
                event(competitionId), teams, european ? 4 : 1);
    }

    private CalendarEvent event(long competitionId) {
        CalendarEvent event = new CalendarEvent();
        event.setCompetitionId(competitionId);
        event.setMatchday(1);
        event.setSeason(1);
        return event;
    }

    private Set<Long> competitionIds(List<List<MatchdayBatchProcessor.SimulationPlan>> waves) {
        return waves.stream()
                .flatMap(List::stream)
                .map(plan -> plan.event().getCompetitionId())
                .collect(java.util.stream.Collectors.toSet());
    }
}
