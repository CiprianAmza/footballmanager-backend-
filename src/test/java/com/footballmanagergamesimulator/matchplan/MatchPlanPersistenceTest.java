package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistence invariants: a plan survives a reload with identical slots and
 * resolved contributions, and is reused (never duplicated) for a fixture.
 */
@DataJpaTest
class MatchPlanPersistenceTest {

    @Autowired private MatchPlanRepository repository;
    @Autowired private TestEntityManager em;

    private final MatchPlanningService planning = new MatchPlanningService(new MatchEngineConfig());

    @Test
    void createReload_sameSlotsAndContributions() {
        // 2-1 after 90 + 1 ET goal for home + shootout 5-4.
        MatchPlan plan = planning.plan("CTIM:777", 12345L, 10L, 20L, 2, 1, 1, 0, 5, 4);
        // Resolve a couple of slots so we can prove contributions survive.
        plan.getGoalSlots().get(0).resolve(101L, 102L);
        plan.getGoalSlots().get(1).resolve(201L, null);

        repository.saveAndFlush(plan);
        em.clear(); // force a real reload from the DB, not the identity cache

        MatchPlan reloaded = repository.findByFixtureKey("CTIM:777").orElseThrow();

        assertEquals(12345L, reloaded.getSeed());
        assertEquals(MatchPlanningService.ALGORITHM_VERSION, reloaded.getAlgorithmVersion());
        assertEquals(2, reloaded.getHomeScore90());
        assertEquals(1, reloaded.getAwayScore90());
        assertEquals(1, reloaded.getHomeScoreET());
        assertEquals(5, reloaded.getHomeShootout());
        assertEquals(4, reloaded.getAwayShootout());
        assertTrue(reloaded.hadExtraTime());
        assertTrue(reloaded.hadShootout());

        List<GoalSlot> before = plan.getGoalSlots();
        List<GoalSlot> after = reloaded.getGoalSlots();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            GoalSlot b = before.get(i);
            GoalSlot a = after.get(i); // @OrderBy slotIndex ASC guarantees order
            assertEquals(b.getSlotIndex(), a.getSlotIndex());
            assertEquals(b.getMinute(), a.getMinute());
            assertEquals(b.getTeamId(), a.getTeamId());
            assertEquals(b.getPhase(), a.getPhase());
            assertEquals(b.getGoalType(), a.getGoalType());
            assertEquals(b.getScorerId(), a.getScorerId());
            assertEquals(b.getAssistId(), a.getAssistId());
            assertEquals(b.isResolved(), a.isResolved());
        }
    }

    @Test
    void fixtureKey_isReusedNotDuplicated() {
        MatchPlan first = planning.plan("CTIM:999", 1L, 10L, 20L, 1, 0);
        repository.saveAndFlush(first);

        assertTrue(repository.existsByFixtureKey("CTIM:999"));
        MatchPlan found = repository.findByFixtureKey("CTIM:999").orElseThrow();
        assertEquals(first.getId(), found.getId());
        assertEquals(1, repository.findAll().size());
    }
}
