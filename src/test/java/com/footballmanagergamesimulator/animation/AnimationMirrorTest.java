package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationMirrorTest {
    private final AnimationDirector director = new AnimationDirector();

    @Test void secondHalfIsExactXMirrorWithoutChangingOutcomeOrPossession() {
        AnimationReplay first = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, null)).replay();
        AnimationReplay second = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 60, null)).replay();
        assertTrue(first.scoringTeamAttacksRight()); assertFalse(second.scoringTeamAttacksRight());
        for (int frame = 0; frame < first.frames().size(); frame++) {
            AnimationFrame a = first.frames().get(frame); AnimationFrame b = second.frames().get(frame);
            assertEquals(round(100 - a.ball().x()), b.ball().x()); assertEquals(a.ball().y(), b.ball().y());
            assertEquals(a.ballCarrierId(), b.ballCarrierId());
            for (int p = 0; p < a.positions().size(); p++) {
                assertEquals(round(100 - a.positions().get(p).x()), b.positions().get(p).x());
                assertEquals(a.positions().get(p).y(), b.positions().get(p).y());
            }
        }
        assertEquals(first.events(), second.events()); assertEquals(first.outcome(), second.outcome());
    }

    @Test void firstHalfStoppageIsNotMirroredEarly() {
        assertTrue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 47, null)).replay().homeAttacksRight());
        assertFalse(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 48, null)).replay().homeAttacksRight());
    }

    private static double round(double value) { return Math.round(value * 10.0) / 10.0; }
}
