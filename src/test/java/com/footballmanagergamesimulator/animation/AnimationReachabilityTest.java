package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every participant physically reaches the ball: the ball is never teleported and never
 * hands over to a receiver who has not arrived (the previously observed ~14-unit gap).
 */
class AnimationReachabilityTest {
    private final AnimationDirector director = new AnimationDirector();

    private static int indexOf(List<PlayerSnapshot> players, long id) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).playerId() == id) return i;
        return -1;
    }

    @Test void everyTransferHandsOverExactlyWhenTheReceiverIsOnTheBall() {
        for (AnimationPhase phase : AnimationPhase.values())
            for (AnimationOutcome outcome : AnimationOutcome.values())
                for (Long assist : new Long[]{ASSISTER, null})
                    for (int slot = 0; slot < 4; slot++) {
                        AnimationReplay replay = director.direct(spec(phase, outcome, slot, 35, assist)).replay();
                        for (AnimationEvent event : replay.events()) {
                            if (!"PASS".equals(event.type()) && !"LOOSE".equals(event.type())) continue;
                            int receiver = indexOf(replay.players(), event.toPlayerId());
                            int possessionFrame = firstPossessionFrame(replay, event);
                            assertTrue(possessionFrame >= 0, "receiver never gets the ball");
                            AnimationFrame frame = replay.frames().get(possessionFrame);
                            double gap = frame.ball().distanceTo(frame.positions().get(receiver));
                            assertTrue(gap < 0.1, "receiver " + event.toPlayerId() + " was " + gap + " from the ball");
                        }
                    }
    }

    @Test void theBallNeverTeleports() {
        AnimationPhysicsProfile profile = AnimationPhysicsProfile.defaults();
        for (AnimationOutcome outcome : AnimationOutcome.values()) for (Long assist : new Long[]{ASSISTER, null}) {
            AnimationReplay replay = director.direct(spec(AnimationPhase.OPEN_PLAY, outcome, 2, 40, assist)).replay();
            for (int f = 1; f < replay.frames().size(); f++) {
                double step = replay.frames().get(f).ball().distanceTo(replay.frames().get(f - 1).ball());
                assertTrue(step <= profile.maxBallStep(), "ball jumped " + step);
            }
        }
    }

    @Test void theFirstToucherRunsOntoTheBallRatherThanStartingOnIt() {
        // The opening carrier begins in their formation slot and moves the ball onto the first
        // action, so the ball is not planted at a fixed target in frame 0.
        AnimationReplay replay = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, ASSISTER)).replay();
        long firstCarrier = replay.frames().get(0).ballCarrierId();
        assertNotEquals(0, firstCarrier);
        int idx = indexOf(replay.players(), firstCarrier);
        PitchPoint start = replay.frames().get(0).positions().get(idx);
        double travelled = 0;
        for (int f = 1; f < replay.frames().size() && replay.frames().get(f).ballCarrierId() == firstCarrier; f++)
            travelled = Math.max(travelled, start.distanceTo(replay.frames().get(f).positions().get(idx)));
        assertTrue(travelled > 1.0, "opening carrier did not move onto the ball");
    }

    private static int firstPossessionFrame(AnimationReplay replay, AnimationEvent transfer) {
        for (int f = transfer.frame() + 1; f < replay.frames().size(); f++) {
            long carrier = replay.frames().get(f).ballCarrierId();
            if (carrier == 0) continue;
            return carrier == transfer.toPlayerId() ? f : -1;
        }
        return -1;
    }
}
