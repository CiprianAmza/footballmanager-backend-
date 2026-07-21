package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-2 invariants: instant execution of a plan into canonical events. */
class InstantMatchExecutorTest {

    private final MatchEngineConfig config = new MatchEngineConfig();
    private final MatchPlanningService planning = new MatchPlanningService(config);
    private final ContributionResolver resolver = new ContributionResolver(config);
    private final InstantMatchExecutor executor = new InstantMatchExecutor(resolver);

    private final InstantMatchExecutor.MatchContext ctx =
            new InstantMatchExecutor.MatchContext("CTIM:1", 100L, 1, 5);

    private Contributor p(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    private Lineup xi(long base) {
        return new Lineup(List.of(
                p(base + 1, "GK"), p(base + 2, "DC"), p(base + 3, "DC"), p(base + 4, "DL"),
                p(base + 5, "MC"), p(base + 6, "MC"), p(base + 7, "AMR"), p(base + 8, "AML"),
                p(base + 9, "ST"), p(base + 10, "ST"), p(base + 11, "DR")), List.of());
    }

    @Test
    void goalEvents_sumToScoreline() {
        MatchPlan plan = planning.plan("fx", 42L, 10L, 20L, 3, 2);
        List<MatchEvent> events = executor.execute(plan, xi(100), xi(200), ctx);
        long goals = events.stream().filter(e -> "goal".equals(e.getEventType())).count();
        assertEquals(5, goals);
        long homeGoals = events.stream()
                .filter(e -> "goal".equals(e.getEventType()) && e.getTeamId() == 10L).count();
        assertEquals(3, homeGoals);
    }

    @Test
    void everyScorer_wasOnPitch_neverGoalkeeper() {
        MatchPlan plan = planning.plan("fx", 7L, 10L, 20L, 4, 3);
        List<MatchEvent> events = executor.execute(plan, xi(100), xi(200), ctx);
        for (MatchEvent e : events) {
            if (!"goal".equals(e.getEventType())) continue;
            boolean home = e.getTeamId() == 10L;
            long gkId = home ? 101L : 201L;
            assertNotEquals(gkId, e.getPlayerId(), "GK scored");
        }
    }

    @Test
    void subbedOffPlayer_cannotScoreAfterExit() {
        // Home ST (110) subbed off at minute 30 for bench player 199.
        Lineup home = new Lineup(xi(100).getStartingXI(),
                List.of(new Lineup.SubMove(30, 110L, p(199L, "ST"))));
        MatchPlan plan = planning.plan("fx", 55L, 10L, 20L, 6, 0);
        List<MatchEvent> events = executor.execute(plan, home, xi(200), ctx);
        for (MatchEvent e : events) {
            if ("goal".equals(e.getEventType()) && e.getPlayerId() == 110L) {
                assertTrue(e.getMinute() <= 30, "player 110 scored at " + e.getMinute() + " after being subbed off");
            }
        }
    }

    @Test
    void projection_matchesEvents() {
        MatchPlan plan = planning.plan("fx", 9L, 10L, 20L, 3, 2);
        List<MatchEvent> events = executor.execute(plan, xi(100), xi(200), ctx);
        Map<Long, MatchEventProjection.Tally> tally = MatchEventProjection.aggregate(events);
        int totalGoals = tally.values().stream().mapToInt(t -> t.goals).sum();
        long eventGoals = events.stream().filter(e -> "goal".equals(e.getEventType())).count();
        assertEquals(eventGoals, totalGoals);
    }

    @Test
    void sameSeed_sameScorers() {
        MatchPlan a = planning.plan("fx", 321L, 10L, 20L, 3, 2);
        MatchPlan b = planning.plan("fx", 321L, 10L, 20L, 3, 2);
        List<Long> sa = executor.execute(a, xi(100), xi(200), ctx).stream()
                .filter(e -> "goal".equals(e.getEventType())).map(MatchEvent::getPlayerId).toList();
        List<Long> sb = executor.execute(b, xi(100), xi(200), ctx).stream()
                .filter(e -> "goal".equals(e.getEventType())).map(MatchEvent::getPlayerId).toList();
        assertEquals(sa, sb);
    }
}
