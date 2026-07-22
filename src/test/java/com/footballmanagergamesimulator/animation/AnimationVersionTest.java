package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two frozen generator versions coexist; a historical V1 replay keeps its exact fingerprint. */
class AnimationVersionTest {
    private final AnimationDirector director = new AnimationDirector();
    private final AnimationRecipeCodec codec = new AnimationRecipeCodec();

    /** Frozen golden fingerprint of the V1 replay for the canonical fixture. */
    private static final long GOLDEN_V1 = -9121886052796786029L;

    private static MatchMomentSpec versioned(int version) {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        return new MatchMomentSpec("CTIM:VER", 0, PLAN_SEED, version, 30, 2, MatchPeriod.FIRST_HALF,
                HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER, ASSISTER, players, null);
    }

    @Test void v1GoldenReplayKeepsItsExactFingerprintWhileV2IsRegistered() {
        // V2 is registered in the catalog; the V1 golden must be byte-for-byte unchanged.
        AnimationReplay v1 = director.direct(versioned(1)).replay();
        assertEquals(GOLDEN_V1, v1.fingerprint());
        // A stored V1 recipe round-tripped through JSON regenerates the same golden.
        AnimationRecipe decoded = codec.decode(codec.encode(director.direct(versioned(1)).recipe()));
        assertEquals(GOLDEN_V1, director.replay(decoded).fingerprint());
        // The two frozen versions are genuinely different implementations.
        assertNotEquals(GOLDEN_V1, director.direct(versioned(2)).replay().fingerprint());
    }

    @Test void bothFrozenVersionsRenderDistinctValidReplays() {
        AnimationInvariantValidator validator = new AnimationInvariantValidator(AnimationPhysicsProfile.defaults());
        AnimationReplay v1 = director.direct(versioned(1)).replay();
        AnimationReplay v2 = director.direct(versioned(2)).replay();
        assertEquals(1, v1.renderedWithVersion());
        assertEquals(2, v2.renderedWithVersion());
        assertTrue(validator.validate(v1, versioned(1)).isEmpty());
        assertTrue(validator.validate(v2, versioned(2)).isEmpty());
        // Independently frozen implementations must actually differ.
        assertNotEquals(v1.fingerprint(), v2.fingerprint());
    }

    @Test void unknownVersionFailsExplicitly() {
        assertThrows(UnsupportedAnimationVersionException.class, () -> director.direct(versioned(99)));
    }

    @Test void historicalV1RecipeReplaysIdenticallyAfterV2Exists() {
        var directed = director.direct(versioned(1));
        AnimationRecipe decoded = codec.decode(codec.encode(directed.recipe()));
        AnimationReplay regenerated = director.replay(decoded);
        assertExact(directed.replay(), regenerated);
        assertEquals(1, regenerated.renderedWithVersion());
    }
}
