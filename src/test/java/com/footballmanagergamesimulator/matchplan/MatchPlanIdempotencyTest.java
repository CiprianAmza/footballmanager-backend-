package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end idempotency of the persisting pipeline: calling it twice for the
 * same fixture must not duplicate the plan, its slots, or its events.
 */
@DataJpaTest
class MatchPlanIdempotencyTest {

    @Autowired private MatchPlanRepository planRepository;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchAppearanceRepository appearanceRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchParticipantRepository participantRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchSubstitutionRepository substitutionRepository;

    private MatchPlanService service;
    private LineupAdapter lineupAdapter;
    private CompetitionTeamInfoMatchRepository fixtureRepository;

    private Contributor p(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    private Lineup xi(long base) {
        return new Lineup(List.of(
                p(base + 1, "GK"), p(base + 2, "DC"), p(base + 3, "MC"), p(base + 4, "MC"),
                p(base + 5, "AMR"), p(base + 6, "AML"), p(base + 7, "ST"), p(base + 8, "ST"),
                p(base + 9, "DL"), p(base + 10, "DR"), p(base + 11, "DC")), List.of());
    }

    @BeforeEach
    void setup() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        MatchPlanningService planning = new MatchPlanningService(cfg);
        InstantMatchExecutor executor = new InstantMatchExecutor(new ContributionResolver(cfg));
        lineupAdapter = mock(LineupAdapter.class);
        fixtureRepository = mock(CompetitionTeamInfoMatchRepository.class);
        when(lineupAdapter.build(eq(10L), any(), anyLong())).thenReturn(xi(100));
        when(lineupAdapter.build(eq(20L), any(), anyLong())).thenReturn(xi(200));
        when(fixtureRepository.findByIdForUpdate(anyLong()))
                .thenReturn(java.util.Optional.of(new CompetitionTeamInfoMatch()));

        service = new MatchPlanService();
        ReflectionTestUtils.setField(service, "planningService", planning);
        ReflectionTestUtils.setField(service, "lineupAdapter", lineupAdapter);
        ReflectionTestUtils.setField(service, "instantExecutor", executor);
        ReflectionTestUtils.setField(service, "matchEventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "matchPlanRepository", planRepository);
        ReflectionTestUtils.setField(service, "matchAppearanceRepository", appearanceRepository);
        ReflectionTestUtils.setField(service, "matchParticipantRepository", participantRepository);
        ReflectionTestUtils.setField(service, "matchSubstitutionRepository", substitutionRepository);
        ReflectionTestUtils.setField(service, "fixtureRepository", fixtureRepository);
        ReflectionTestUtils.setField(service, "engineConfig", cfg);
    }

    @Test
    void calledTwice_noDuplicatePlanSlotsOrEvents() {
        List<MatchEvent> first = service.buildAndPersist(
                "CTIM:42", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 3, 2);
        List<MatchEvent> second = service.buildAndPersist(
                "CTIM:42", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 3, 2);

        assertEquals(1, planRepository.count(), "exactly one plan");
        MatchPlan plan = planRepository.findByFixtureKey("CTIM:42").orElseThrow();
        assertEquals(5, plan.getGoalSlots().size(), "5 goal slots, not duplicated");

        // Same events returned both times; total persisted equals first run's count.
        assertEquals(first.size(), second.size());
        assertEquals(first.size(), eventRepository.findByFixtureKey("CTIM:42").size());

        // Zero duplicates on the natural key (fixtureKey, slotIndex, eventType).
        Set<String> keys = new HashSet<>();
        for (MatchEvent e : eventRepository.findByFixtureKey("CTIM:42")) {
            assertTrue(keys.add(e.getFixtureKey() + "|" + e.getSlotIndex() + "|" + e.getEventType()),
                    "duplicate event " + e.getSlotIndex() + "/" + e.getEventType());
        }
    }

    @Test
    void appearancesPersisted_everyScorerCoveredAndNotDuplicatedOnRetry() {
        service.buildAndPersist("CTIM:55", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 3, 2);
        service.buildAndPersist("CTIM:55", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 3, 2); // retry

        MatchPlan plan = planRepository.findByFixtureKey("CTIM:55").orElseThrow();
        List<MatchAppearance> apps = appearanceRepository.findByMatchPlan(plan);
        assertEquals(22, apps.size(), "11 per team, not duplicated on retry");

        for (MatchEvent e : eventRepository.findByFixtureKey("CTIM:55")) {
            if (!"goal".equals(e.getEventType())) continue;
            boolean covered = apps.stream()
                    .anyMatch(a -> a.getPlayerId() == e.getPlayerId() && a.onPitchAt(e.getMinute()));
            assertTrue(covered, "scorer " + e.getPlayerId() + " had no appearance at minute " + e.getMinute());
        }
    }

    @Test
    void goallessMatch_reusedByPlanStatus_notReExecuted() {
        // A 0-0 has no events; reuse must key off the COMPLETED plan, not event
        // existence, so a retry does not re-run the pipeline.
        service.buildAndPersist("CTIM:7", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 0, 0);
        service.buildAndPersist("CTIM:7", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 0, 0);

        assertEquals(1, planRepository.count());
        assertEquals(MatchPlan.Status.COMPLETED,
                planRepository.findByFixtureKey("CTIM:7").orElseThrow().getStatus());
        assertTrue(eventRepository.findByFixtureKey("CTIM:7").isEmpty());
        // Second call short-circuited: lineups built only for the first run (2 teams).
        verify(lineupAdapter, times(2)).build(anyLong(), any(), anyLong());
    }

    @Test
    void retryPreservesExtraTimeScoresAndEvents() {
        // Knockout still level after ET (1-1 at 90, 1-1 in ET), decided 5-4 on
        // penalties: ET goals AND a shootout coexist; an ET winner would preclude
        // the shootout.
        List<MatchEvent> first = service.buildAndPersist("CTIM:77", 100L, 1, 5, 10L, 20L,
                "4-4-2", "4-4-2", 1, 1, 1, 1, 5, 4);
        List<MatchEvent> second = service.buildAndPersist("CTIM:77", 100L, 1, 5, 10L, 20L,
                "4-4-2", "4-4-2", 1, 1, 1, 1, 5, 4);

        assertEquals(first.size(), second.size());
        MatchPlan plan = planRepository.findByFixtureKey("CTIM:77").orElseThrow();
        assertEquals(1, plan.getHomeScoreET());
        assertEquals(1, plan.getAwayScoreET());
        assertEquals(5, plan.getHomeShootout());
        assertEquals(4, plan.getAwayShootout());
        // 2 regular + 2 ET goal slots; the shootout adds none.
        assertEquals(4, plan.getGoalSlots().size());
        // 4 goal events persisted (+ any assists), no shootout events, no duplicates.
        long goalEvents = eventRepository.findByFixtureKey("CTIM:77").stream()
                .filter(e -> "goal".equals(e.getEventType())).count();
        assertEquals(4, goalEvents);
    }

    @Test
    void committedMatch_isTerminalAndNotReExecuted() {
        service.buildAndPersist("CTIM:8", 100L, 1, 5, 10L, 20L,
                "4-4-2", "4-4-2", 1, 0);
        MatchPlan plan = planRepository.findByFixtureKey("CTIM:8").orElseThrow();
        plan.setStatus(MatchPlan.Status.COMMITTED);
        planRepository.saveAndFlush(plan);

        service.buildAndPersist("CTIM:8", 100L, 1, 5, 10L, 20L,
                "4-4-2", "4-4-2", 1, 0);

        verify(lineupAdapter, times(2)).build(anyLong(), any(), anyLong());
        assertEquals(MatchPlan.Status.COMMITTED,
                planRepository.findByFixtureKey("CTIM:8").orElseThrow().getStatus());
    }

    @Test
    void committedStaleVersion_isImmutableAndNeverRegenerated() {
        service.buildAndPersist("CTIM:9", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 1, 0);
        MatchPlan plan = planRepository.findByFixtureKey("CTIM:9").orElseThrow();
        long originalId = plan.getId();
        plan.setStatus(MatchPlan.Status.COMMITTED);
        ReflectionTestUtils.setField(plan, "algorithmVersion", "matchplan-OLD"); // stale + committed
        planRepository.saveAndFlush(plan);

        service.buildAndPersist("CTIM:9", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 1, 0);

        MatchPlan after = planRepository.findByFixtureKey("CTIM:9").orElseThrow();
        assertEquals(originalId, after.getId(), "a committed match must never be rewritten");
        assertEquals(MatchPlan.Status.COMMITTED, after.getStatus());
        verify(lineupAdapter, times(2)).build(anyLong(), any(), anyLong()); // no regeneration
    }

    @Test
    void markCommitted_finalizesCompletedPlanAndMakesItImmutable() {
        service.buildAndPersist("CTIM:12", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 2, 1);
        service.markCommitted("CTIM:12");

        assertEquals(MatchPlan.Status.COMMITTED,
                planRepository.findByFixtureKey("CTIM:12").orElseThrow().getStatus());

        // Now immutable: a further call reuses it, never rebuilds.
        service.buildAndPersist("CTIM:12", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 2, 1);
        verify(lineupAdapter, times(2)).build(anyLong(), any(), anyLong());
    }

    @Test
    void completedStaleVersion_isRegenerated() {
        service.buildAndPersist("CTIM:11", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 1, 0);
        MatchPlan plan = planRepository.findByFixtureKey("CTIM:11").orElseThrow();
        ReflectionTestUtils.setField(plan, "algorithmVersion", "matchplan-OLD"); // stale, still COMPLETED
        planRepository.saveAndFlush(plan);

        service.buildAndPersist("CTIM:11", 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 1, 0);

        MatchPlan after = planRepository.findByFixtureKey("CTIM:11").orElseThrow();
        assertEquals(MatchPlanningService.ALGORITHM_VERSION, after.getAlgorithmVersion());
        assertEquals(1, planRepository.count(), "old plan replaced, not duplicated");
        verify(lineupAdapter, times(4)).build(anyLong(), any(), anyLong()); // 2 first run + 2 regen
    }

    @Test
    void replayPreservesGoalBeforeAssistOrder() {
        MatchEvent assist = canonicalEvent("CTIM:88", 0, MatchEvent.ORDER_ASSIST, "assist");
        MatchEvent goal = canonicalEvent("CTIM:88", 0, MatchEvent.ORDER_GOAL, "goal");
        eventRepository.saveAllAndFlush(List.of(assist, goal));

        List<MatchEvent> ordered = eventRepository
                .findByFixtureKeyOrderBySlotIndexAscEventOrderAsc("CTIM:88");

        assertEquals(List.of("goal", "assist"),
                ordered.stream().map(MatchEvent::getEventType).toList());
    }

    private MatchEvent canonicalEvent(String fixtureKey, int slotIndex, int eventOrder, String type) {
        MatchEvent event = new MatchEvent();
        event.setFixtureKey(fixtureKey);
        event.setSlotIndex(slotIndex);
        event.setEventOrder(eventOrder);
        event.setEventType(type);
        return event;
    }
}
