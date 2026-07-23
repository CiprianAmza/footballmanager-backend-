package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationDeterminismTest {
    private final AnimationDirector director = new AnimationDirector();

    @Test void sameInputProducesExactlySameReplayAndRecipe() {
        var first = director.direct(spec()); var second = director.direct(spec());
        assertExact(first.replay(), second.replay()); assertEquals(first.recipe(), second.recipe());
    }

    @Test void determinismHoldsForEveryPhaseAndOutcome() {
        for (AnimationPhase phase : AnimationPhase.values()) for (AnimationOutcome outcome : AnimationOutcome.values()) {
            MatchMomentSpec moment = spec(phase, outcome, 3, 48, null);
            assertExact(director.direct(moment).replay(), director.direct(moment).replay());
        }
    }

    @Test void fixtureIdentityChangesVariationDeterministically() {
        long first = director.direct(spec("CTIM:501", AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, null)).replay().fingerprint();
        long second = director.direct(spec("CTIM:502", AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, null)).replay().fingerprint();
        assertNotEquals(first, second);
    }

    @Test void slotIdentityChangesVariationDeterministically() {
        long first = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, null)).replay().fingerprint();
        long second = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 1, 30, null)).replay().fingerprint();
        assertNotEquals(first, second);
    }

    @Test void seedUsesAllRequiredFields() {
        long seed = AnimationSeed.derive(1, "F", 2, 3);
        assertEquals(seed, AnimationSeed.derive(1, "F", 2, 3));
        assertNotEquals(seed, AnimationSeed.derive(2, "F", 2, 3));
        assertNotEquals(seed, AnimationSeed.derive(1, "G", 2, 3));
        assertNotEquals(seed, AnimationSeed.derive(1, "F", 3, 3));
        assertNotEquals(seed, AnimationSeed.derive(1, "F", 2, 4));
    }
}
