package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.animation.AnimationDirector;
import com.footballmanagergamesimulator.animation.AnimationV3Settings;
import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.matchplan.Contributor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The presentation-only V3 adapter is a feature-flagged bridge from a canonical goal to a
 * {@link GoalAnimationData}: off by default (so the legacy path is untouched), non-authoritative
 * (it only renders already-decided canonical facts), deterministic and restart-stable, and it
 * degrades to empty (legacy fallback) whenever a moment cannot be rendered.
 */
class AnimationV3GoalAdapterTest {

    private static final String FIXTURE = "CTIM:777";
    private static final long HOME = 10;
    private static final long AWAY = 20;
    private static final long PLAN_SEED = 987654321L;
    private static final long SCORER = 101;
    private static final long ASSISTER = 102;

    /** A GoalAnimationContext that needs no repositories: no kit lookup, zero stoppage. */
    private static GoalAnimationContext stubContext() {
        return new GoalAnimationContext() {
            @Override public void attachKits(GoalAnimationData data, long s, long d) { /* no repos in unit test */ }
        };
    }

    private static AnimationV3GoalAdapter adapter(boolean flagOn) {
        AnimationV3Settings settings = new AnimationV3Settings() {
            @Override public boolean enabled() { return flagOn; }
        };
        return new AnimationV3GoalAdapter(settings, new AnimationDirector(), stubContext());
    }

    private static Contributor c(long id, String pos, double rating) {
        return new Contributor(id, "P" + id, pos, rating, 12, 12, 12, 100, false, false);
    }

    /** Scoring team on-pitch: scorer (ST) + assister (MC) + a full spread. */
    private static List<Contributor> attackers() {
        List<Contributor> a = new ArrayList<>();
        a.add(c(SCORER, "ST", 15));
        a.add(c(ASSISTER, "MC", 14));
        a.add(c(100, "GK", 13));
        String[] rest = {"DL", "DC", "DR", "DM", "ML", "MR", "AMC", "AMR"};
        for (int i = 0; i < rest.length; i++) a.add(c(110 + i, rest[i], 12));
        return a;
    }

    /** Defending team on-pitch: always includes a GK. */
    private static List<Contributor> defenders() {
        List<Contributor> d = new ArrayList<>();
        d.add(c(200, "GK", 13));
        String[] rest = {"DL", "DC", "DR", "DM", "MC", "ML", "MR", "AMC", "AML", "ST"};
        for (int i = 0; i < rest.length; i++) d.add(c(210 + i, rest[i], 12));
        return d;
    }

    private static Map<Long, Integer> shirts(List<Contributor>... groups) {
        Map<Long, Integer> m = new HashMap<>();
        int n = 1;
        for (List<Contributor> g : groups) for (Contributor c : g) m.put(c.playerId(), n++);
        return m;
    }

    private Optional<GoalAnimationData> build(AnimationV3GoalAdapter adapter, int slotIndex, int minute,
                                              boolean extraTime, String goalType, Long assisterId) {
        List<Contributor> att = attackers();
        List<Contributor> def = defenders();
        return adapter.tryBuildCanonicalGoal(FIXTURE, slotIndex, PLAN_SEED, minute, extraTime,
                HOME, AWAY, HOME, goalType, SCORER, assisterId, att, def, shirts(att, def));
    }

    @Test void flagOffProducesNothingSoLegacyPathRuns() {
        assertTrue(build(adapter(false), 3, 30, false, "OPEN_PLAY", ASSISTER).isEmpty());
        assertFalse(adapter(false).enabled());
    }

    @Test void flagOnRendersAndPreservesEveryCanonicalFact() {
        GoalAnimationData d = build(adapter(true), 7, 63, false, "OPEN_PLAY", ASSISTER).orElseThrow();
        assertEquals(63, d.getMinute());
        assertEquals(7, d.getSlotIndex());
        assertEquals(FIXTURE, d.getFixtureKey());
        assertEquals(HOME, d.getScoringTeamId());
        assertEquals(AWAY, d.getDefendingTeamId());
        assertEquals(HOME, d.getHomeTeamId());
        assertEquals(SCORER, d.getScorerPlayerId());
        assertEquals(ASSISTER, d.getAssisterPlayerId());
        assertEquals("GOAL", d.getOutcome());
        assertEquals("OPEN_PLAY", d.getAnimationType());
        assertEquals(AnimationDirector.CURRENT_GENERATOR_VERSION, d.getGeneratorVersion());
        assertEquals(2, d.getGeneratorVersion());
        // Frames are present and totalFrames indexes the last one.
        assertFalse(d.getFrames().isEmpty());
        assertEquals(d.getFrames().size() - 1, d.getTotalFrames());
        // Player order is attacking team first (frame positions follow the same order).
        assertEquals(HOME, d.getPlayers().get(0).getTeamId());
        // The scorer's overlay carries their real shirt number from the canonical roster.
        assertTrue(d.getScorerNumber() > 0);
    }

    @Test void goalTypeMapsToTheAnimationPhase() {
        assertEquals("PENALTY", build(adapter(true), 1, 30, false, "PENALTY", null).orElseThrow().getAnimationType());
        assertEquals("FREE_KICK", build(adapter(true), 1, 30, false, "FREE_KICK", ASSISTER).orElseThrow().getAnimationType());
        // HEADER has no distinct engine phase; it renders as an open-play finish.
        assertEquals("OPEN_PLAY", build(adapter(true), 1, 30, false, "HEADER", ASSISTER).orElseThrow().getAnimationType());
    }

    @Test void sameInputsRegenerateAnIdenticalAnimation() {
        AnimationV3GoalAdapter a = adapter(true);
        GoalAnimationData first = build(a, 5, 40, false, "OPEN_PLAY", ASSISTER).orElseThrow();
        GoalAnimationData again = build(a, 5, 40, false, "OPEN_PLAY", ASSISTER).orElseThrow();
        assertEquals(checksum(first), checksum(again), "a refresh/restart must regenerate the identical animation");
    }

    @Test void twoGoalsAtTheSameMinuteStayDistinctBySlotIndex() {
        AnimationV3GoalAdapter a = adapter(true);
        GoalAnimationData slot3 = build(a, 3, 55, false, "OPEN_PLAY", ASSISTER).orElseThrow();
        GoalAnimationData slot4 = build(a, 4, 55, false, "OPEN_PLAY", ASSISTER).orElseThrow();
        assertEquals(slot3.getMinute(), slot4.getMinute());
        assertNotEquals(slot3.getSlotIndex(), slot4.getSlotIndex());
        assertNotEquals(checksum(slot3), checksum(slot4), "distinct slots at one minute get distinct animations");
    }

    @Test void firstAndSecondHalfAttackInOppositeDirections() {
        boolean firstHalf = build(adapter(true), 1, 20, false, "OPEN_PLAY", ASSISTER).orElseThrow().isHomeAttacksRight();
        boolean secondHalf = build(adapter(true), 1, 70, false, "OPEN_PLAY", ASSISTER).orElseThrow().isHomeAttacksRight();
        assertNotEquals(firstHalf, secondHalf);
    }

    @Test void extraTimeHalvesAttackInOppositeDirections() {
        boolean et1 = build(adapter(true), 1, 95, true, "OPEN_PLAY", ASSISTER).orElseThrow().isHomeAttacksRight();
        boolean et2 = build(adapter(true), 1, 115, true, "OPEN_PLAY", ASSISTER).orElseThrow().isHomeAttacksRight();
        assertNotEquals(et1, et2);
    }

    @Test void unassistedGoalRendersWithNoAssister() {
        GoalAnimationData d = build(adapter(true), 2, 33, false, "OPEN_PLAY", null).orElseThrow();
        assertNull(d.getAssisterPlayerId());
        assertEquals(SCORER, d.getScorerPlayerId());
    }

    @Test void anUnrenderableMomentDegradesToEmptyForLegacyFallback() {
        // Defending side without a goalkeeper cannot form a valid MatchMomentSpec → empty (legacy fallback),
        // never a dropped or corrupt animation.
        List<Contributor> att = attackers();
        List<Contributor> defNoGk = new ArrayList<>();
        for (int i = 0; i < 10; i++) defNoGk.add(c(300 + i, "DC", 12));
        Optional<GoalAnimationData> out = adapter(true).tryBuildCanonicalGoal(FIXTURE, 1, PLAN_SEED, 30, false,
                HOME, AWAY, HOME, "OPEN_PLAY", SCORER, ASSISTER, att, defNoGk, shirts(att, defNoGk));
        assertTrue(out.isEmpty());
    }

    private static long checksum(GoalAnimationData d) {
        long h = 1125899906842597L;
        h = 31 * h + d.getFrames().size();
        for (GoalAnimationData.AnimationFrame f : d.getFrames()) {
            h = 31 * h + Double.hashCode(f.getBallX());
            h = 31 * h + Double.hashCode(f.getBallY());
            h = 31 * h + Long.hashCode(f.getBallCarrierId());
            for (double[] p : f.getPositions()) {
                h = 31 * h + Double.hashCode(p[0]);
                h = 31 * h + Double.hashCode(p[1]);
            }
        }
        return h;
    }
}
