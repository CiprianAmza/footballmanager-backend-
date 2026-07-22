package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationMirrorTest {
    private final AnimationDirector director = new AnimationDirector();

    @Test void oppositeDirectionIsExactXMirrorWithoutChangingOutcomeOrPossession() {
        AnimationReplay first = director.direct(specPeriod(MatchPeriod.FIRST_HALF, 0, 30, null)).replay();
        AnimationReplay second = director.direct(specPeriod(MatchPeriod.SECOND_HALF, 0, 30, null)).replay();
        assertTrue(first.scoringTeamAttacksRight());
        assertFalse(second.scoringTeamAttacksRight());
        for (int frame = 0; frame < first.frames().size(); frame++) {
            AnimationFrame a = first.frames().get(frame);
            AnimationFrame b = second.frames().get(frame);
            assertEquals(round(100 - a.ball().x()), b.ball().x());
            assertEquals(a.ball().y(), b.ball().y());
            assertEquals(a.ballCarrierId(), b.ballCarrierId());
            for (int p = 0; p < a.positions().size(); p++) {
                assertEquals(round(100 - a.positions().get(p).x()), b.positions().get(p).x());
                assertEquals(a.positions().get(p).y(), b.positions().get(p).y());
            }
        }
        assertEquals(first.events(), second.events());
        assertEquals(first.outcome(), second.outcome());
    }

    @Test void everyPeriodPersistsItsOwnExplicitDirection() {
        // Home attacks right in each first-half; left in each second-half. Direction comes from the
        // explicit period, never the minute, so ET first and ET second halves are modelled distinctly.
        assertTrue(direct(MatchPeriod.FIRST_HALF, 30).homeAttacksRight());
        assertFalse(direct(MatchPeriod.SECOND_HALF, 60).homeAttacksRight());
        assertTrue(direct(MatchPeriod.EXTRA_TIME_FIRST_HALF, 95).homeAttacksRight());
        assertFalse(direct(MatchPeriod.EXTRA_TIME_SECOND_HALF, 110).homeAttacksRight());
    }

    @Test void extraTimeHalvesAtDifferentMinutesDoNotShareDirection() {
        AnimationReplay et1 = direct(MatchPeriod.EXTRA_TIME_FIRST_HALF, 95);
        AnimationReplay et2 = direct(MatchPeriod.EXTRA_TIME_SECOND_HALF, 110);
        assertNotEquals(et1.homeAttacksRight(), et2.homeAttacksRight());
        assertEquals(MatchPeriod.EXTRA_TIME_FIRST_HALF, et1.period());
        assertEquals(MatchPeriod.EXTRA_TIME_SECOND_HALF, et2.period());
    }

    private AnimationReplay direct(MatchPeriod period, int minute) {
        return director.direct(specPeriod(period, 0, minute, null)).replay();
    }

    private static double round(double value) { return Math.round(value / PitchPoint.PRECISION) * PitchPoint.PRECISION; }
}
