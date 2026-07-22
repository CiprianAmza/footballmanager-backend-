package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;

import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.assertFramesIdentical;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Identity + idempotency: the same canonical input always renders the same frames. */
class AnimationDeterminismTest {

    private final AnimationDirector director = new AnimationDirector();

    @Test
    void sameInputProducesIdenticalFrames() {
        MatchMomentSpec s = spec();
        AnimationDirector.DirectedAnimation first = director.direct(s);
        AnimationDirector.DirectedAnimation second = director.direct(s);
        assertFramesIdentical(first.replay(), second.replay());
        assertEquals(first.recipe(), second.recipe());
    }

    @Test
    void identicalAcrossPhasesAndOutcomes() {
        for (AnimationPhase phase : AnimationPhase.values()) {
            for (AnimationOutcome outcome : AnimationOutcome.values()) {
                MatchMomentSpec s = spec(phase, outcome, 3, 55, null);
                assertFramesIdentical(director.direct(s).replay(), director.direct(s).replay());
            }
        }
    }

    @Test
    void differentSlotIndexProducesDeterministicVariation() {
        AnimationReplay slot0 = director.direct(spec(AnimationPhase.OPEN_PLAY,
                AnimationOutcome.GOAL, 0, 30, AnimationTestFixtures.ASSISTER_ID)).replay();
        AnimationReplay slot1 = director.direct(spec(AnimationPhase.OPEN_PLAY,
                AnimationOutcome.GOAL, 1, 30, AnimationTestFixtures.ASSISTER_ID)).replay();
        assertNotEquals(slot0.fingerprint(), slot1.fingerprint(),
                "different slotIndex must vary the animation");
        // And each variant is itself stable.
        assertEquals(slot1.fingerprint(), director.direct(spec(AnimationPhase.OPEN_PLAY,
                AnimationOutcome.GOAL, 1, 30, AnimationTestFixtures.ASSISTER_ID)).replay().fingerprint());
    }

    @Test
    void differentFixtureKeyProducesDeterministicVariation() {
        AnimationReplay a = director.direct(spec("1_5_3_10_20", AnimationPhase.OPEN_PLAY,
                AnimationOutcome.GOAL, 0, 30, null)).replay();
        AnimationReplay b = director.direct(spec("1_5_3_20_10", AnimationPhase.OPEN_PLAY,
                AnimationOutcome.GOAL, 0, 30, null)).replay();
        assertNotEquals(a.fingerprint(), b.fingerprint(),
                "different fixtureKey must vary the animation");
    }

    @Test
    void seedDerivationIsStable() {
        long s1 = AnimationSeeds.derive(42, "k", 3, 1);
        long s2 = AnimationSeeds.derive(42, "k", 3, 1);
        assertEquals(s1, s2);
        assertNotEquals(s1, AnimationSeeds.derive(42, "k", 4, 1));
        assertNotEquals(s1, AnimationSeeds.derive(42, "k2", 3, 1));
        assertNotEquals(s1, AnimationSeeds.derive(43, "k", 3, 1));
        assertNotEquals(s1, AnimationSeeds.derive(42, "k", 3, 2));
    }
}
