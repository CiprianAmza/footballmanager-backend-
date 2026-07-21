package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-1 invariants for the canonical MatchPlan pipeline. */
class MatchPlanFoundationTest {

    private final MatchEngineConfig config = new MatchEngineConfig();
    private final MatchPlanningService planning = new MatchPlanningService(config);
    private final ContributionResolver resolver = new ContributionResolver(config);

    private Contributor player(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    // ---------------- planning ----------------

    @Test
    void goalSlotCount_matchesScoreline() {
        MatchPlan plan = planning.plan("fx", 42L, 10L, 20L, 3, 2);
        assertEquals(5, plan.getGoalSlots().size());
        long home = plan.getGoalSlots().stream().filter(s -> s.getTeamId() == 10L).count();
        long away = plan.getGoalSlots().stream().filter(s -> s.getTeamId() == 20L).count();
        assertEquals(3, home);
        assertEquals(2, away);
    }

    @Test
    void regularGoals_landWithinNinety() {
        MatchPlan plan = planning.plan("fx", 7L, 10L, 20L, 4, 1);
        for (GoalSlot s : plan.getGoalSlots()) {
            assertEquals(GoalPhase.REGULAR_TIME, s.getPhase());
            assertTrue(s.getMinute() >= 1 && s.getMinute() <= 90, "minute " + s.getMinute());
        }
    }

    @Test
    void extraTimeGoals_arePhasedAndLate_shootoutIsNotAGoal() {
        // 1-1 after 90, one ET goal for home, home wins shootout 4-2.
        MatchPlan plan = planning.plan("fx", 99L, 10L, 20L, 1, 1, 1, 0, 4, 2);
        assertTrue(plan.hadExtraTime());
        assertTrue(plan.hadShootout());
        // 2 regular + 1 ET = 3 goal slots; shootout adds none.
        assertEquals(3, plan.getGoalSlots().size());
        GoalSlot et = plan.getGoalSlots().stream()
                .filter(s -> s.getPhase() == GoalPhase.EXTRA_TIME).findFirst().orElseThrow();
        assertTrue(et.getMinute() >= 91 && et.getMinute() <= 120, "ET minute " + et.getMinute());
        // Football goals exclude the shootout.
        assertEquals(2, plan.getHomeGoals()); // 1 reg + 1 ET
        assertEquals(1, plan.getAwayGoals());
    }

    @Test
    void samSeed_producesSameMinutes() {
        MatchPlan a = planning.plan("fx", 123L, 10L, 20L, 2, 2);
        MatchPlan b = planning.plan("fx", 123L, 10L, 20L, 2, 2);
        List<Integer> ma = a.getGoalSlots().stream().map(GoalSlot::getMinute).toList();
        List<Integer> mb = b.getGoalSlots().stream().map(GoalSlot::getMinute).toList();
        assertEquals(ma, mb);
    }

    // ---------------- knockout: extra time & shootout ----------------

    @Test
    void singleLegExtraTime_hasEtSlots_noShootout() {
        // 1-1 after 90, home wins 2-1 with an extra-time goal.
        MatchPlan plan = planning.plan("fx", 1L, 10L, 20L, 1, 1, 1, 0, -1, -1);
        assertTrue(plan.hadExtraTime());
        assertFalse(plan.hadShootout());
        assertEquals(3, plan.getGoalSlots().size()); // 2 regular + 1 ET, no shootout
        assertEquals(1, plan.getGoalSlots().stream()
                .filter(s -> s.getPhase() == GoalPhase.EXTRA_TIME).count());
        assertEquals(2, plan.getHomeGoals());
        assertEquals(1, plan.getAwayGoals());
    }

    @Test
    void singleLegPenalties_shootoutProducesNoGoalSlots() {
        // 1-1 after 90 and 0-0 in extra time, decided 5-4 on penalties.
        MatchPlan plan = planning.plan("fx", 1L, 10L, 20L, 1, 1, 0, 0, 5, 4);
        assertTrue(plan.hadExtraTime());  // extra time was played (goalless)
        assertTrue(plan.hadShootout());
        assertEquals(2, plan.getGoalSlots().size()); // only the two 90' goals
        assertEquals(1, plan.getHomeGoals());
        assertEquals(1, plan.getAwayGoals()); // shootout 5-4 excluded from goals
    }

    @Test
    void extraTimeGoal_assignedToScoringTeam() {
        // 0-0 after 90, the only goal is an away extra-time winner.
        MatchPlan plan = planning.plan("fx", 3L, 10L, 20L, 0, 0, 0, 1, -1, -1);
        GoalSlot et = plan.getGoalSlots().stream()
                .filter(s -> s.getPhase() == GoalPhase.EXTRA_TIME).findFirst().orElseThrow();
        assertEquals(20L, et.getTeamId());
    }

    @Test
    void twoLegSecondLeg_usesLegScoreNotAggregate() {
        // Leg 2 is its own fixture: 2-1 at 90 + a home ET goal. The plan stores
        // the leg's goals (3-1), never the two-leg aggregate.
        MatchPlan plan = planning.plan("fx", 5L, 10L, 20L, 2, 1, 1, 0, -1, -1);
        assertEquals(4, plan.getGoalSlots().size()); // 3 + 1
        assertEquals(3, plan.getHomeGoals());
        assertEquals(1, plan.getAwayGoals());
    }

    @Test
    void twoLegSecondLegPenalties_noShootoutGoals() {
        // Leg 2 ends level, extra time goalless, decided on penalties.
        MatchPlan plan = planning.plan("fx", 6L, 10L, 20L, 0, 0, 0, 0, 3, 2);
        assertTrue(plan.hadShootout());
        assertEquals(0, plan.getGoalSlots().size());
        assertEquals(0, plan.getHomeGoals());
        assertEquals(0, plan.getAwayGoals());
    }

    // ---------------- resolver ----------------

    @Test
    void scorer_isAlwaysOnPitch_andNeverGoalkeeper() {
        List<Contributor> onPitch = List.of(
                player(1L, "GK"), player(2L, "ST"), player(3L, "MC"), player(4L, "DC"));
        Random rng = new Random(1);
        for (int i = 0; i < 200; i++) {
            GoalSlot slot = new GoalSlot(10L, 30, GoalPhase.REGULAR_TIME, "OPEN_PLAY");
            resolver.resolve(slot, onPitch, rng);
            assertTrue(slot.isResolved());
            assertNotEquals(1L, slot.getScorerId(), "GK must never score");
            assertTrue(List.of(2L, 3L, 4L).contains(slot.getScorerId()));
        }
    }

    @Test
    void subbedOffPlayer_cannotScore() {
        // Player 9 is NOT on the pitch -> can never be the scorer.
        List<Contributor> onPitch = List.of(player(2L, "ST"), player(3L, "MC"));
        Random rng = new Random(5);
        for (int i = 0; i < 200; i++) {
            GoalSlot slot = new GoalSlot(10L, 80, GoalPhase.REGULAR_TIME, "OPEN_PLAY");
            resolver.resolve(slot, onPitch, rng);
            assertNotEquals(9L, slot.getScorerId());
        }
    }

    @Test
    void penaltyGoal_goesToDesignatedTaker() {
        Contributor taker = new Contributor(7L, "Taker", "MC", 12.0, 10, 10, 10, 100.0, true, false);
        List<Contributor> onPitch = List.of(player(2L, "ST"), taker, player(3L, "DC"));
        GoalSlot slot = new GoalSlot(10L, 55, GoalPhase.REGULAR_TIME, "PENALTY");
        resolver.resolve(slot, onPitch, new Random(3));
        assertEquals(7L, slot.getScorerId());
        assertNull(slot.getAssistId(), "penalties have no assist");
    }

    @Test
    void resolvedSlot_isNotReResolved() {
        List<Contributor> onPitch = List.of(player(2L, "ST"), player(3L, "MC"));
        GoalSlot slot = new GoalSlot(10L, 40, GoalPhase.REGULAR_TIME, "OPEN_PLAY");
        resolver.resolve(slot, onPitch, new Random(1));
        Long firstScorer = slot.getScorerId();
        resolver.resolve(slot, List.of(player(3L, "MC")), new Random(2));
        assertEquals(firstScorer, slot.getScorerId(), "replay must not change a resolved slot");
    }
}
