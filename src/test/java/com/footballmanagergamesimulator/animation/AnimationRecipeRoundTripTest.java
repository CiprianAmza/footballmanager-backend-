package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;

import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.ASSISTER_ID;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.assertFramesIdentical;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The compact recipe regenerates byte-identical frames, incl. after JSON persistence. */
class AnimationRecipeRoundTripTest {

    private final AnimationDirector director = new AnimationDirector();
    private final AnimationRecipeCodec codec = new AnimationRecipeCodec();

    @Test
    void recipeRegeneratesIdenticalFrames() {
        for (AnimationPhase phase : AnimationPhase.values()) {
            for (AnimationOutcome outcome : AnimationOutcome.values()) {
                MatchMomentSpec s = spec(phase, outcome, 2, 48, null);
                AnimationDirector.DirectedAnimation directed = director.direct(s);
                AnimationReplay regenerated = director.replay(directed.recipe());
                assertFramesIdentical(directed.replay(), regenerated);
                assertEquals(directed.replay().patternId(), regenerated.patternId());
            }
        }
    }

    @Test
    void serializedRecipeRoundTripsToIdenticalFrames() {
        MatchMomentSpec s = spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 1, 63, ASSISTER_ID);
        AnimationDirector.DirectedAnimation directed = director.direct(s);

        String json = codec.toJson(directed.recipe());
        AnimationRecipe restored = codec.fromJson(json);
        assertEquals(directed.recipe(), restored, "recipe survives JSON round-trip");

        AnimationReplay regenerated = director.replay(restored);
        assertFramesIdentical(directed.replay(), regenerated);
    }

    @Test
    void recipeCarriesTheFullCanonicalIdentity() {
        MatchMomentSpec s = spec(AnimationPhase.PENALTY, AnimationOutcome.SAVE, 3, 78, null);
        AnimationRecipe recipe = director.direct(s).recipe();
        assertEquals(s.fixtureKey(), recipe.fixtureKey());
        assertEquals(s.slotIndex(), recipe.slotIndex());
        assertEquals(s.planSeed(), recipe.planSeed());
        assertEquals(s.outcome(), recipe.outcome());
        assertEquals(s.minute(), recipe.minute());
        assertEquals(s.scorerId(), recipe.scorerId());
        assertEquals(s.assisterId(), recipe.assisterId());
        assertEquals(AnimationSeeds.derive(s.planSeed(), s.fixtureKey(), s.slotIndex(),
                s.generatorVersion()), recipe.seed());
    }

    @Test
    void recipePinsMotionLimitsAcrossConfigurationChanges() {
        AnimationDirector strictDirector =
                new AnimationDirector(new AnimationMotionLimits(0.60, 0.25));
        AnimationDirector defaultDirector = new AnimationDirector();
        AnimationDirector.DirectedAnimation directed = strictDirector.direct(spec());

        AnimationReplay regeneratedUnderDifferentRuntimeConfig =
                defaultDirector.replay(directed.recipe());
        assertFramesIdentical(directed.replay(), regeneratedUnderDifferentRuntimeConfig);
        assertEquals(new AnimationMotionLimits(0.60, 0.25),
                directed.recipe().motionLimits());
    }
}
