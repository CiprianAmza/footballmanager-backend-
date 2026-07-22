package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Half-aware mirroring: a second-half moment renders as the exact x-mirror of
 * the first-half build (the seed ignores the minute), and mirroring never
 * changes the outcome, events or possession.
 */
class AnimationMirrorTest {

    private final AnimationDirector director = new AnimationDirector();
    private final AnimationPhysicsValidator validator = new AnimationPhysicsValidator();

    @Test
    void secondHalfMirrorsFirstHalfWithoutChangingTheOutcome() {
        MatchMomentSpec firstHalf = spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, null);
        MatchMomentSpec secondHalf = spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 60, null);

        AnimationReplay fh = director.direct(firstHalf).replay();
        AnimationReplay sh = director.direct(secondHalf).replay();

        assertTrue(fh.homeAttacksRight());
        assertFalse(sh.homeAttacksRight());
        assertTrue(fh.scoringTeamAttacksRight(), "home attacker → right in the 1st half");
        assertFalse(sh.scoringTeamAttacksRight(), "home attacker → left in the 2nd half");

        assertEquals(fh.frames().size(), sh.frames().size());
        for (int f = 0; f < fh.frames().size(); f++) {
            ReplayFrame a = fh.frames().get(f);
            ReplayFrame b = sh.frames().get(f);
            assertEquals(round1(100 - a.ballX()), b.ballX(), 0.0, "ballX mirrored, frame " + f);
            assertEquals(a.ballY(), b.ballY(), 0.0, "ballY unchanged, frame " + f);
            assertEquals(a.ballCarrierId(), b.ballCarrierId(), "possession unchanged, frame " + f);
            for (int i = 0; i < a.positions().size(); i++) {
                assertEquals(round1(100 - a.positions().get(i)[0]), b.positions().get(i)[0], 0.0);
                assertEquals(a.positions().get(i)[1], b.positions().get(i)[1], 0.0);
            }
        }
        assertEquals(fh.events(), sh.events(), "mirroring never touches the events");
        assertEquals(fh.outcome(), sh.outcome());
        assertTrue(validator.validate(sh, secondHalf).isEmpty(),
                "mirrored replay still satisfies every invariant");
    }

    @Test
    void stoppageTimeMinutesStayInTheFirstHalf() {
        // firstHalfStoppage=2 in the fixtures → minute 47 is still first half.
        MatchMomentSpec stoppage = spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 47, null);
        assertTrue(director.direct(stoppage).replay().homeAttacksRight());
        MatchMomentSpec after = spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 48, null);
        assertFalse(director.direct(after).replay().homeAttacksRight());
    }

    @Test
    void mirroredOutcomesStillValidateForAllOutcomeTypes() {
        for (AnimationOutcome outcome : AnimationOutcome.values()) {
            MatchMomentSpec s = spec(AnimationPhase.OPEN_PLAY, outcome, 1, 75, null);
            AnimationReplay r = director.direct(s).replay();
            List<String> violations = validator.validate(r, s);
            assertTrue(violations.isEmpty(), outcome + " mirrored: " + violations);
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
