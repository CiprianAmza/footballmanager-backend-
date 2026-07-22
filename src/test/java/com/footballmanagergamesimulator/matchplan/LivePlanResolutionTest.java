package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-vs-instant resolution semantics of the shared pipeline: the canonical plan
 * fixes the goal chronology (side/minute) identically for both paths, while the
 * scorer/assist are resolved from the players actually on the pitch — so the live
 * path's real user substitutions decide who scores, without ever changing the
 * score or when the goals happen.
 */
class LivePlanResolutionTest {

    private final MatchEngineConfig config = new MatchEngineConfig();
    private final MatchPlanningService planning = new MatchPlanningService(config);
    private final InstantMatchExecutor executor = new InstantMatchExecutor(new ContributionResolver(config));
    private final InstantMatchExecutor.MatchContext ctx =
            new InstantMatchExecutor.MatchContext("CTIM:1", 100L, 1, 5);

    private Contributor gk(long id) {
        return new Contributor(id, "GK" + id, "GK", 15.0, 0, 0, 0, 100.0, false, false);
    }

    private Contributor out(long id, String pos, int finishing, double rating) {
        return new Contributor(id, "P" + id, pos, rating, finishing, 12, 12, 100.0, false, false);
    }

    private Lineup xi(long base) {
        return new Lineup(List.of(
                out(base + 1, "GK", 5, 12), out(base + 2, "DC", 6, 13), out(base + 3, "DC", 6, 13),
                out(base + 4, "DL", 7, 13), out(base + 5, "MC", 9, 14), out(base + 6, "MC", 9, 14),
                out(base + 7, "AMR", 12, 15), out(base + 8, "AML", 12, 15), out(base + 9, "ST", 15, 16),
                out(base + 10, "ST", 15, 16), out(base + 11, "DR", 7, 13)), List.of());
    }

    private List<int[]> homeGoalMinutes(List<MatchEvent> events) { // [minute] of home (team 10) goals
        List<int[]> out = new ArrayList<>();
        for (MatchEvent e : events) {
            if ("goal".equals(e.getEventType()) && e.getTeamId() == 10L) out.add(new int[]{e.getMinute()});
        }
        return out;
    }

    // ---------------- chronology parity ----------------

    @Test
    void instantAndLive_haveIdenticalGoalChronology_evenWithAUserSub() {
        // Two independent plan instances with the same seed → identical schedule.
        MatchPlan planInstant = planning.plan("fx", 4242L, 10L, 20L, 3, 2);
        MatchPlan planLive = planning.plan("fx", 4242L, 10L, 20L, 3, 2);

        Lineup instantHome = xi(100);
        Lineup liveHome = LiveLineupFactory.build(xi(100).getStartingXI(), List.of(),
                List.of(new LiveLineupFactory.UserSub(109L, out(199, "ST", 15, 16), 55)));

        List<String> instantChrono = chronology(executor.execute(planInstant, instantHome, xi(200), ctx));
        List<String> liveChrono = chronology(executor.execute(planLive, liveHome, xi(200), ctx));

        // Same goal minutes and sides regardless of the substitution.
        assertEquals(instantChrono, liveChrono);
    }

    private List<String> chronology(List<MatchEvent> events) {
        List<String> out = new ArrayList<>();
        for (MatchEvent e : events) {
            if ("goal".equals(e.getEventType())) out.add(e.getMinute() + ":" + e.getTeamId());
        }
        return out;
    }

    // ---------------- scorer resolved from the on-pitch set ----------------

    @Test
    void substitute_isCreditedWithCanonicalGoalsAfterEntering() {
        // Home fields ten goalkeepers (weight 0) + one outfielder, so exactly one
        // eligible scorer is on the pitch at any minute → the pick is deterministic.
        // The lone outfielder (120) is replaced at minute 2 by the star sub (199).
        List<Contributor> starters = new ArrayList<>();
        for (long i = 101; i <= 110; i++) starters.add(gk(i));
        starters.add(out(120, "ST", 15, 16));
        Lineup home = LiveLineupFactory.build(starters, List.of(out(199, "ST", 18, 18)),
                List.of(new LiveLineupFactory.UserSub(120L, out(199, "ST", 18, 18), 2)));

        MatchPlan plan = planning.plan("fx", 77L, 10L, 20L, 5, 0);
        List<MatchEvent> events = executor.execute(plan, home, xi(200), ctx);

        long homeGoals = events.stream()
                .filter(e -> "goal".equals(e.getEventType()) && e.getTeamId() == 10L).count();
        assertEquals(5, homeGoals, "all five home goals present");
        for (MatchEvent e : events) {
            if (!"goal".equals(e.getEventType()) || e.getTeamId() != 10L) continue;
            long expected = e.getMinute() < 2 ? 120L : 199L; // sub is on from minute 2
            assertEquals(expected, e.getPlayerId(),
                    "home goal at " + e.getMinute() + " must be scored by the on-pitch outfielder");
        }
    }

    @Test
    void subbedOffPlayer_scoresNoCanonicalGoalAfterHisExitMinute() {
        // Realistic XI; the user subs off player 109 at minute 45.
        Lineup home = LiveLineupFactory.build(xi(100).getStartingXI(),
                List.of(out(199, "ST", 15, 16)),
                List.of(new LiveLineupFactory.UserSub(109L, out(199, "ST", 15, 16), 45)));

        MatchPlan plan = planning.plan("fx", 313L, 10L, 20L, 6, 0);
        List<MatchEvent> events = executor.execute(plan, home, xi(200), ctx);

        for (MatchEvent e : events) {
            if ("goal".equals(e.getEventType()) && e.getPlayerId() == 109L) {
                assertTrue(e.getMinute() < 45,
                        "player 109 scored at " + e.getMinute() + " after being subbed off at 45");
            }
            if ("goal".equals(e.getEventType()) && e.getPlayerId() == 199L) {
                assertTrue(e.getMinute() >= 45,
                        "substitute 199 scored at " + e.getMinute() + " before entering at 45");
            }
        }
    }

    // ---------------- LiveLineupFactory ----------------

    @Test
    void liveLineupFactory_assignsConsecutivePerTeamSequence_inMinuteOrder() {
        // Two subs supplied out of minute order → sorted, consecutive sequence 0,1.
        Lineup lineup = LiveLineupFactory.build(xi(100).getStartingXI(),
                List.of(out(198, "MC", 12, 14), out(199, "ST", 15, 16)),
                List.of(new LiveLineupFactory.UserSub(107L, out(199, "ST", 15, 16), 70),
                        new LiveLineupFactory.UserSub(108L, out(198, "MC", 12, 14), 55)));

        List<Lineup.SubMove> subs = lineup.getSubs();
        assertEquals(2, subs.size());
        assertEquals(0, subs.get(0).sequence());
        assertEquals(55, subs.get(0).minute());
        assertEquals(198L, subs.get(0).on().playerId());
        assertEquals(1, subs.get(1).sequence());
        assertEquals(70, subs.get(1).minute());
    }

    @Test
    void liveLineupFactory_appearancesDerivedFromActualSubs() {
        Lineup lineup = LiveLineupFactory.build(xi(100).getStartingXI(),
                List.of(out(199, "ST", 15, 16)),
                List.of(new LiveLineupFactory.UserSub(109L, out(199, "ST", 15, 16), 60)));

        Lineup.Appearance off = lineup.appearances().stream()
                .filter(a -> a.playerId() == 109L).findFirst().orElseThrow();
        Lineup.Appearance on = lineup.appearances().stream()
                .filter(a -> a.playerId() == 199L).findFirst().orElseThrow();

        assertEquals(0, off.startMinute());
        assertEquals(60, off.exitMinute());
        assertEquals(60, off.minutesPlayed(90));
        assertEquals(60, on.startMinute());
        assertNull(on.exitMinute(), "the substitute finished the match");
        assertEquals(30, on.minutesPlayed(90));
    }
}
