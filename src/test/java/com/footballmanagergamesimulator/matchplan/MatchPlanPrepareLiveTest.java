package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * LIVE sub-slice 1 — {@code prepareLivePlan}: the canonical plan is persisted
 * BEFORE kickoff as a {@code PLANNED} plan with unresolved goal slots and the
 * kickoff participant snapshot, and reused (never duplicated or re-preselected)
 * across a browser refresh / mid-playback recovery. No scorer is preselected and
 * no event is emitted at kickoff — that is deferred to slot-by-slot resolution.
 */
@DataJpaTest
class MatchPlanPrepareLiveTest {

    @Autowired private MatchPlanRepository planRepository;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchParticipantRepository participantRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchSubstitutionRepository substitutionRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchAppearanceRepository appearanceRepository;

    private MatchPlanService service;

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
        CompetitionTeamInfoMatchRepository fixtureRepository = mock(CompetitionTeamInfoMatchRepository.class);
        when(fixtureRepository.findByIdForUpdate(anyLong()))
                .thenReturn(Optional.of(new CompetitionTeamInfoMatch()));

        service = new MatchPlanService();
        ReflectionTestUtils.setField(service, "planningService", planning);
        ReflectionTestUtils.setField(service, "lineupAdapter", mock(LineupAdapter.class));
        ReflectionTestUtils.setField(service, "instantExecutor", executor);
        ReflectionTestUtils.setField(service, "matchEventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "matchPlanRepository", planRepository);
        ReflectionTestUtils.setField(service, "matchAppearanceRepository", appearanceRepository);
        ReflectionTestUtils.setField(service, "matchParticipantRepository", participantRepository);
        ReflectionTestUtils.setField(service, "matchSubstitutionRepository", substitutionRepository);
        ReflectionTestUtils.setField(service, "fixtureRepository", fixtureRepository);
        ReflectionTestUtils.setField(service, "userContext",
                mock(com.footballmanagergamesimulator.user.UserContext.class));
        ReflectionTestUtils.setField(service, "engineConfig", cfg);
    }

    @Test
    void prepareLivePlan_persistsPlannedPlan_unresolvedSlots_noScorersNoEvents() {
        MatchPlan plan = service.prepareLivePlan(
                "CTIM:42", 100L, 1, 5, 10L, 20L, xi(100), xi(200), 2, 1);

        assertEquals(1, planRepository.count());
        MatchPlan saved = planRepository.findByFixtureKey("CTIM:42").orElseThrow();
        assertEquals(MatchPlan.Status.PLANNED, saved.getStatus(), "not completed at kickoff");
        assertEquals(3, saved.getGoalSlots().size(), "2+1 goal slots scheduled");
        for (GoalSlot slot : saved.getGoalSlots()) {
            assertFalse(slot.isResolved(), "no scorer preselected at kickoff");
            assertNull(slot.getScorerId());
        }
        // Fixed schedule present (side/minute), but no goal events yet.
        assertTrue(eventRepository.findByFixtureKey("CTIM:42").isEmpty(), "no events emitted at kickoff");
        // Kickoff participant snapshot: 11 per team.
        assertEquals(22, participantRepository.findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(plan).size());
        // Two home slots + one away slot, matching the decided scoreline.
        long homeSlots = saved.getGoalSlots().stream().filter(s -> s.getTeamId() == 10L).count();
        assertEquals(2, homeSlots);
    }

    @Test
    void prepareLivePlan_refreshReusesPlan_noDuplicateParticipantsOrSlots() {
        MatchPlan first = service.prepareLivePlan("CTIM:55", 100L, 1, 5, 10L, 20L, xi(100), xi(200), 3, 0);
        MatchPlan second = service.prepareLivePlan("CTIM:55", 100L, 1, 5, 10L, 20L, xi(100), xi(200), 3, 0);

        assertEquals(first.getId(), second.getId(), "same plan reused on refresh");
        assertEquals(1, planRepository.count());
        assertEquals(3, planRepository.findByFixtureKey("CTIM:55").orElseThrow().getGoalSlots().size());
        assertEquals(22, participantRepository.findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(second).size(),
                "kickoff snapshot not duplicated on refresh");
        assertEquals(MatchPlan.Status.PLANNED, second.getStatus());
    }

    @Test
    void prepareLivePlan_recoversInProgressPlan_preservingAlreadyResolvedSlots() {
        MatchPlan plan = service.prepareLivePlan("CTIM:60", 100L, 1, 5, 10L, 20L, xi(100), xi(200), 2, 1);
        // Simulate playback having resolved the first slot and moved to IN_PROGRESS.
        GoalSlot firstSlot = plan.getGoalSlots().get(0);
        firstSlot.resolve(105L, 106L);
        plan.setStatus(MatchPlan.Status.IN_PROGRESS);
        planRepository.saveAndFlush(plan);

        // Recovery (refresh mid-playback) reuses the same plan with its resolved state.
        MatchPlan recovered = service.prepareLivePlan("CTIM:60", 100L, 1, 5, 10L, 20L, xi(100), xi(200), 2, 1);
        assertEquals(plan.getId(), recovered.getId());
        assertEquals(MatchPlan.Status.IN_PROGRESS, recovered.getStatus());
        GoalSlot recoveredFirst = recovered.getGoalSlots().stream()
                .filter(s -> s.getSlotIndex() == firstSlot.getSlotIndex()).findFirst().orElseThrow();
        assertTrue(recoveredFirst.isResolved(), "resolved slot survives recovery");
        assertEquals(105L, recoveredFirst.getScorerId());
        long stillUnresolved = recovered.getGoalSlots().stream().filter(s -> !s.isResolved()).count();
        assertEquals(2, stillUnresolved, "future slots stay unresolved");
    }

    @Test
    void prepareLivePlan_committedPlanIsImmutable() {
        MatchPlan plan = service.prepareLivePlan("CTIM:8", 100L, 1, 5, 10L, 20L, xi(100), xi(200), 1, 0);
        plan.setStatus(MatchPlan.Status.COMMITTED);
        planRepository.saveAndFlush(plan);

        MatchPlan again = service.prepareLivePlan("CTIM:8", 100L, 1, 5, 10L, 20L, xi(100), xi(200), 1, 0);
        assertEquals(plan.getId(), again.getId());
        assertEquals(MatchPlan.Status.COMMITTED, again.getStatus());
        assertEquals(1, planRepository.count());
    }
}
