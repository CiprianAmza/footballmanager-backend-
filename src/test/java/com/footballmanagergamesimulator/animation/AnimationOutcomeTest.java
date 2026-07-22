package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationOutcomeTest {
    private final AnimationDirector director = new AnimationDirector();

    private static AnimationEvent result(AnimationReplay replay) { return replay.events().get(replay.events().size() - 1); }
    private static int index(List<PlayerSnapshot> players, long id) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).playerId() == id) return i;
        return -1;
    }

    @Test void goalCrossesBetweenPosts() {
        AnimationReplay replay = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, ASSISTER)).replay();
        AnimationFrame frame = replay.frames().get(result(replay).frame());
        assertEquals(100, frame.ball().x(), 0.2); assertTrue(frame.ball().y() > 44 && frame.ball().y() < 56);
    }

    @Test void missNeverCrossesTheMouth() {
        AnimationReplay replay = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.MISS, 1, 30, null)).replay();
        for (AnimationFrame frame : replay.frames())
            assertFalse(Math.abs(frame.ball().x() - 100) <= 0.2 && frame.ball().y() > 44 && frame.ball().y() < 56);
    }

    @Test void saveIntersectsGoalkeeper() {
        AnimationReplay replay = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.SAVE, 2, 30, null)).replay();
        AnimationEvent event = result(replay); AnimationFrame frame = replay.frames().get(event.frame());
        int keeper = index(replay.players(), event.fromPlayerId());
        assertTrue(replay.players().get(keeper).goalkeeper()); assertEquals(0, frame.ball().distanceTo(frame.positions().get(keeper)), 0.2);
    }

    @Test void blockedShotIntersectsDefender() {
        AnimationReplay replay = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.BLOCKED, 3, 30, null)).replay();
        AnimationEvent event = result(replay); AnimationFrame frame = replay.frames().get(event.frame());
        int blocker = index(replay.players(), event.fromPlayerId());
        assertTrue(blocker >= 11); assertEquals(0, frame.ball().distanceTo(frame.positions().get(blocker)), 0.2);
    }

    @Test void canonicalScorerTakesFinalShotForEveryPhase() {
        for (AnimationPhase phase : AnimationPhase.values()) {
            AnimationReplay replay = director.direct(spec(phase, AnimationOutcome.GOAL, 0, 30, null)).replay();
            AnimationEvent shot = replay.events().stream().filter(event -> "SHOT".equals(event.type())).findFirst().orElseThrow();
            assertEquals(SCORER, shot.fromPlayerId());
        }
    }

    @Test void canonicalAssistGivesLastPassForEveryPhase() {
        for (AnimationPhase phase : AnimationPhase.values()) {
            AnimationReplay replay = director.direct(spec(phase, AnimationOutcome.GOAL, 0, 30, ASSISTER)).replay();
            AnimationEvent lastPass = null;
            for (AnimationEvent event : replay.events()) if ("PASS".equals(event.type())) lastPass = event;
            assertNotNull(lastPass); assertEquals(ASSISTER, lastPass.fromPlayerId()); assertEquals(SCORER, lastPass.toPlayerId());
        }
    }

    @Test void onlySnapshotPlayersAppear() {
        MatchMomentSpec moment = spec(); AnimationReplay replay = director.direct(moment).replay();
        assertEquals(22, replay.players().size());
        assertTrue(moment.playersOnPitch().containsAll(replay.players()));
        for (AnimationFrame frame : replay.frames()) assertEquals(replay.players().size(), frame.positions().size());
    }
}
