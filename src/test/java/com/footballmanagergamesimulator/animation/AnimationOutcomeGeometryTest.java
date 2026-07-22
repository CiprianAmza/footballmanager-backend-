package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.ASSISTER_ID;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.SCORER_ID;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit outcome-geometry and role assertions (beyond the validator sweep). */
class AnimationOutcomeGeometryTest {

    private final AnimationDirector director = new AnimationDirector();

    private static ReplayEvent finalEvent(AnimationReplay r) {
        return r.events().get(r.events().size() - 1);
    }

    private static double goalLineX(AnimationReplay r) {
        return r.scoringTeamAttacksRight() ? 100 : 0;
    }

    @Test
    void goalCrossesTheLineBetweenThePosts() {
        AnimationReplay r = director.direct(
                spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, ASSISTER_ID)).replay();
        ReplayFrame at = r.frames().get(finalEvent(r).frame());
        assertEquals(goalLineX(r), at.ballX(), 0.2, "ball on the goal line");
        assertTrue(at.ballY() > 44 && at.ballY() < 56, "inside the posts: " + at.ballY());
    }

    @Test
    void missNeverEntersTheGoalMouth() {
        AnimationReplay r = director.direct(
                spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.MISS, 1, 30, null)).replay();
        double lineX = goalLineX(r);
        for (ReplayFrame f : r.frames()) {
            boolean onLine = Math.abs(f.ballX() - lineX) <= 0.3;
            assertFalse(onLine && f.ballY() > 44 && f.ballY() < 56,
                    "MISS ball entered the goal mouth at y=" + f.ballY());
        }
    }

    @Test
    void saveIntersectsTheGoalkeeper() {
        AnimationReplay r = director.direct(
                spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.SAVE, 2, 30, null)).replay();
        ReplayEvent save = finalEvent(r);
        int gkIdx = indexOf(r.players(), save.fromPlayerId());
        assertTrue(gkIdx >= r.players().size() - 11, "saver belongs to the defending side");
        ReplayFrame at = r.frames().get(save.frame());
        double[] gk = at.positions().get(gkIdx);
        double d = Math.hypot(gk[0] - at.ballX(), gk[1] - at.ballY());
        assertTrue(d <= AnimationPhysics.GK_REACH, "ball " + d + " from the keeper");
    }

    @Test
    void blockedIntersectsADefender() {
        AnimationReplay r = director.direct(
                spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.BLOCKED, 3, 30, null)).replay();
        ReplayEvent blocked = finalEvent(r);
        int bi = indexOf(r.players(), blocked.fromPlayerId());
        assertTrue(bi >= 11, "blocker is a defending player");
        ReplayFrame at = r.frames().get(blocked.frame());
        double[] blocker = at.positions().get(bi);
        double d = Math.hypot(blocker[0] - at.ballX(), blocker[1] - at.ballY());
        assertTrue(d <= AnimationPhysics.BLOCK_REACH, "ball " + d + " from the blocker");
    }

    @Test
    void scorerTakesTheFinalShotInEveryPhase() {
        for (AnimationPhase phase : AnimationPhase.values()) {
            AnimationReplay r = director.direct(
                    spec(phase, AnimationOutcome.GOAL, 0, 30, null)).replay();
            ReplayEvent shot = r.events().stream()
                    .filter(e -> "SHOT".equals(e.type())).findFirst().orElse(null);
            assertNotNull(shot, phase + ": SHOT event exists");
            assertEquals(SCORER_ID, shot.fromPlayerId(), phase + ": canonical scorer shoots");
            ReplayFrame at = r.frames().get(shot.frame());
            double[] scorer = at.positions().get(indexOf(r.players(), SCORER_ID));
            double d = Math.hypot(scorer[0] - at.ballX(), scorer[1] - at.ballY());
            assertTrue(d <= 3.0, phase + ": scorer touches the ball last (d=" + d + ")");
        }
    }

    @Test
    void assisterDeliversTheFinalPassInEveryPhase() {
        for (AnimationPhase phase : AnimationPhase.values()) {
            AnimationReplay r = director.direct(
                    spec(phase, AnimationOutcome.GOAL, 0, 30, ASSISTER_ID)).replay();
            ReplayEvent lastPass = null;
            for (ReplayEvent e : r.events()) if ("PASS".equals(e.type())) lastPass = e;
            assertNotNull(lastPass, phase + ": an assisted goal must contain a pass");
            assertEquals(ASSISTER_ID, lastPass.fromPlayerId(), phase + ": assister passes last");
            assertEquals(SCORER_ID, lastPass.toPlayerId(), phase + ": final pass to the scorer");
        }
    }

    @Test
    void onlySnapshotPlayersAppear() {
        MatchMomentSpec s = spec();
        AnimationReplay r = director.direct(s).replay();
        List<PlayerSnapshot> expected = new java.util.ArrayList<>(s.attackingPlayers());
        expected.addAll(s.defendingPlayers());
        assertEquals(expected, r.players(), "exactly the snapshot players, attacking side first");
        for (ReplayEvent e : r.events()) {
            if (e.fromPlayerId() != 0) assertTrue(indexOf(r.players(), e.fromPlayerId()) >= 0);
            if (e.toPlayerId() != 0) assertTrue(indexOf(r.players(), e.toPlayerId()) >= 0);
        }
        for (ReplayFrame f : r.frames()) {
            assertEquals(expected.size(), f.positions().size());
            if (f.ballCarrierId() != 0) assertTrue(indexOf(r.players(), f.ballCarrierId()) >= 0);
        }
    }

    private static int indexOf(List<PlayerSnapshot> players, long id) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).playerId() == id) return i;
        return -1;
    }
}
