package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.AdminController;
import com.footballmanagergamesimulator.model.CalendarEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
