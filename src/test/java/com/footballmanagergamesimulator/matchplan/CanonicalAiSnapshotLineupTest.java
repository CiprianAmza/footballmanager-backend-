package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the AI fast-path canonical seam: {@link LineupAdapter#buildFromSnapshot}
 * (the query-free lineup the batch AI path feeds from its warm caches) plus the canonical
 * invariants the fast-path relies on — deterministic pre-planned subs, same-minute goals kept
 * distinct by {@code slotIndex}, and every scorer/assist genuinely on the pitch at the goal
 * minute (a subbed-off player can no longer be credited).
 */
class CanonicalAiSnapshotLineupTest {

    private final MatchEngineConfig config = new MatchEngineConfig();
    private final MatchPlanningService planning = new MatchPlanningService(config);
    private final ContributionResolver resolver = new ContributionResolver(config);
    private final InstantMatchExecutor executor = new InstantMatchExecutor(resolver);
    private final InstantMatchExecutor.MatchContext ctx =
            new InstantMatchExecutor.MatchContext("CTIM:1", 100L, 1, 5);

    private LineupAdapter snapshotAdapter() {
        LineupAdapter adapter = new LineupAdapter();
        ReflectionTestUtils.setField(adapter, "engineConfig", config);
        return adapter;
    }

    private Contributor p(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    private List<Contributor> xi(long base) {
        return List.of(
                p(base + 1, "GK"), p(base + 2, "DC"), p(base + 3, "DC"), p(base + 4, "DL"),
                p(base + 5, "MC"), p(base + 6, "MC"), p(base + 7, "AMR"), p(base + 8, "AML"),
                p(base + 9, "ST"), p(base + 10, "ST"), p(base + 11, "DR"));
    }

    private List<Contributor> bench(long base) {
        return List.of(p(base + 12, "DC"), p(base + 13, "MC"), p(base + 14, "ST"),
                p(base + 15, "AML"), p(base + 16, "DR"));
    }

    @Test
    void buildFromSnapshot_isDeterministic_forSameSeed() {
        LineupAdapter adapter = snapshotAdapter();
        Lineup a = adapter.buildFromSnapshot(xi(100), bench(100), 4242L, true);
        Lineup b = adapter.buildFromSnapshot(xi(100), bench(100), 4242L, true);

        assertEquals(a.getSubs().size(), b.getSubs().size());
        assertFalse(a.getSubs().isEmpty(), "AI snapshot lineup should pre-plan substitutions");
        for (int i = 0; i < a.getSubs().size(); i++) {
            Lineup.SubMove sa = a.getSubs().get(i);
            Lineup.SubMove sb = b.getSubs().get(i);
            assertEquals(sa.sequence(), sb.sequence());
            assertEquals(sa.minute(), sb.minute());
            assertEquals(sa.offPlayerId(), sb.offPlayerId());
            assertEquals(sa.on().playerId(), sb.on().playerId());
        }
    }

    @Test
    void buildFromSnapshot_withoutSubs_isJustTheXi() {
        Lineup lineup = snapshotAdapter().buildFromSnapshot(xi(100), bench(100), 1L, false);
        assertTrue(lineup.getSubs().isEmpty());
        assertEquals(11, lineup.getStartingXI().size());
        assertEquals(5, lineup.getBench().size());
    }

    @Test
    void snapshotLineup_everyGoalContributor_onPitchAtItsMinute() {
        Lineup home = snapshotAdapter().buildFromSnapshot(xi(100), bench(100), 777L, true);
        Lineup away = snapshotAdapter().buildFromSnapshot(xi(200), bench(200), 778L, true);
        MatchPlan plan = planning.plan("CTIM:1", 55L, 10L, 20L, 4, 3);
        List<MatchEvent> events = executor.execute(plan, home, away, ctx);

        for (MatchEvent e : events) {
            Lineup lineup = e.getTeamId() == 10L ? home : away;
            List<Long> onPitchIds = lineup.onPitchAt(e.getMinute()).stream()
                    .map(Contributor::playerId).toList();
            assertTrue(onPitchIds.contains(e.getPlayerId()),
                    e.getEventType() + " by " + e.getPlayerId() + " at min " + e.getMinute()
                            + " who was not on the pitch");
        }
    }

    @Test
    void knockoutPlan_extraTimeGoalsCount_shootoutDoesNot() {
        // 1-1 after 90', 1-0 in extra time, and a shootout carried on the plan (4-2).
        MatchPlan plan = planning.plan("CTIM:1", 33L, 10L, 20L, 1, 1, 1, 0, 4, 2);
        Lineup home = snapshotAdapter().buildFromSnapshot(xi(100), bench(100), 111L, true);
        Lineup away = snapshotAdapter().buildFromSnapshot(xi(200), bench(200), 112L, true);
        List<MatchEvent> events = executor.execute(plan, home, away, ctx);

        long goals = events.stream().filter(e -> "goal".equals(e.getEventType())).count();
        // 90' goals (1+1) + extra-time goals (1+0) = 3; the shootout is NOT a goal.
        assertEquals(3, goals, "goal events == score90 + extra time, never the shootout");

        // Every extra-time goal slot lives in minutes 91..120; the shootout produced no slot.
        long etSlots = plan.getGoalSlots().stream()
                .filter(s -> s.getPhase() == GoalPhase.EXTRA_TIME).count();
        assertEquals(1, etSlots);
        for (GoalSlot s : plan.getGoalSlots()) {
            if (s.getPhase() == GoalPhase.EXTRA_TIME) {
                assertTrue(s.getMinute() >= 91 && s.getMinute() <= 120,
                        "extra-time goal at minute " + s.getMinute());
            }
        }
        assertEquals(4, plan.getHomeShootout());
        assertEquals(2, plan.getAwayShootout());
        // Extra-time scorers were on the pitch at their (91..120) minute.
        for (MatchEvent e : events) {
            Lineup lineup = e.getTeamId() == 10L ? home : away;
            assertTrue(lineup.onPitchAt(e.getMinute()).stream()
                            .anyMatch(c -> c.playerId() == e.getPlayerId()),
                    "contributor " + e.getPlayerId() + " off pitch at " + e.getMinute());
        }
    }

    @Test
    void sameMinuteGoals_stayDistinctBySlotIndex_andBothProjected() {
        // Two home goals at the SAME minute: distinct slot indices must keep them separate.
        GoalSlot s0 = new GoalSlot(10L, 55, GoalPhase.REGULAR_TIME, "OPEN_PLAY");
        s0.setSlotIndex(0);
        GoalSlot s1 = new GoalSlot(10L, 55, GoalPhase.REGULAR_TIME, "OPEN_PLAY");
        s1.setSlotIndex(1);
        MatchPlan plan = new MatchPlan("CTIM:1", 99L, MatchPlanningService.ALGORITHM_VERSION,
                10L, 20L, 2, 0, -1, -1, -1, -1, new ArrayList<>(List.of(s0, s1)));

        Lineup home = new Lineup(xi(100), List.of());
        Lineup away = new Lineup(xi(200), List.of());
        List<MatchEvent> events = executor.execute(plan, home, away, ctx);

        List<MatchEvent> goals = events.stream()
                .filter(e -> "goal".equals(e.getEventType())).toList();
        assertEquals(2, goals.size(), "both same-minute goals are projected");
        assertEquals(55, goals.get(0).getMinute());
        assertEquals(55, goals.get(1).getMinute());
        assertEquals(2, goals.stream().map(MatchEvent::getSlotIndex).distinct().count(),
                "same-minute goals stay distinct by slotIndex");
    }
}
