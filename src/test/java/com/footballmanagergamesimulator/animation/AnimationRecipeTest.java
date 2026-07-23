package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationRecipeTest {
    private final AnimationDirector director = new AnimationDirector();
    private final AnimationRecipeCodec codec = new AnimationRecipeCodec();

    @Test void recipeRegeneratesExactReplayForAllOutcomes() {
        for (AnimationOutcome outcome : AnimationOutcome.values()) {
            var directed = director.direct(spec(AnimationPhase.OPEN_PLAY, outcome, 2, 48, null));
            assertExact(directed.replay(), director.replay(directed.recipe()));
        }
    }

    @Test void jsonRoundTripRegeneratesExactReplay() {
        var directed = director.direct(spec());
        AnimationRecipe decoded = codec.decode(codec.encode(directed.recipe()));
        assertEquals(directed.recipe(), decoded); assertExact(directed.replay(), director.replay(decoded));
    }

    @Test void recipePinsPhysicsAcrossRuntimeConfigurationChanges() {
        AnimationDirector strict = new AnimationDirector(new AnimationPhysicsProfile(0.6, 0.25, 4.0));
        var directed = strict.direct(spec());
        assertExact(directed.replay(), director.replay(directed.recipe()));
    }
}
