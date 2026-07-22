package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Two frozen generator versions coexist. Version 1 is the original engine, preserved
 * byte-for-byte; version 2 is the remediated engine and is current. A pre-upgrade V1
 * recipe — including legacy JSON without the period field — regenerates unchanged.
 */
class AnimationVersionTest {
    private final AnimationDirector director = new AnimationDirector();
    private final AnimationRecipeCodec codec = new AnimationRecipeCodec();

    /** Pre-upgrade golden fingerprint of the original version-1 replay (captured from commit 38e9b15). */
    private static final long GOLDEN_V1 = 4958526529101888831L;
    /** Golden fingerprint of the current version-2 replay for the same canonical facts. */
    private static final long GOLDEN_V2 = -6353409510575470345L;

    private static MatchMomentSpec versioned(int version, MatchPeriod period) {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        return new MatchMomentSpec("CTIM:VER", 0, PLAN_SEED, version, 30, 2, period,
                HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER, ASSISTER, players, null);
    }

    @Test void currentVersionIsTwoAndNewMomentsUseIt() {
        assertEquals(2, AnimationDirector.CURRENT_GENERATOR_VERSION);
        assertEquals(2, director.direct(spec()).replay().renderedWithVersion());
    }

    @Test void frozenVersionOneKeepsItsExactPreUpgradeFingerprint() {
        AnimationReplay v1 = director.direct(versioned(1, null)).replay();
        assertEquals(1, v1.renderedWithVersion());
        assertEquals(GOLDEN_V1, v1.fingerprint());
        assertNull(v1.period(), "legacy replay carries no explicit period");
    }

    @Test void currentVersionTwoIsADistinctFrozenImplementation() {
        AnimationReplay v2 = director.direct(versioned(2, MatchPeriod.FIRST_HALF)).replay();
        assertEquals(2, v2.renderedWithVersion());
        assertEquals(GOLDEN_V2, v2.fingerprint());
        assertNotEquals(GOLDEN_V1, GOLDEN_V2);
    }

    @Test void bothFrozenVersionsRenderValidReplays() {
        MatchMomentSpec s1 = versioned(1, null);
        MatchMomentSpec s2 = versioned(2, MatchPeriod.FIRST_HALF);
        AnimationInvariantValidator validator = new AnimationInvariantValidator(AnimationPhysicsProfile.defaults());
        assertTrue(validator.validate(director.direct(s1).replay(), s1).isEmpty());
        assertTrue(validator.validate(director.direct(s2).replay(), s2).isEmpty());
    }

    @Test void unknownVersionFailsExplicitly() {
        assertThrows(UnsupportedAnimationVersionException.class,
                () -> director.direct(versioned(99, MatchPeriod.FIRST_HALF)));
    }

    @Test void historicalV1RecipeRoundTripsThroughJsonToTheGolden() {
        var directed = director.direct(versioned(1, null));
        AnimationRecipe decoded = codec.decode(codec.encode(directed.recipe()));
        assertEquals(directed.recipe(), decoded);
        AnimationReplay regenerated = director.replay(decoded);
        assertEquals(1, regenerated.renderedWithVersion());
        assertEquals(GOLDEN_V1, regenerated.fingerprint());
    }

    @Test void currentVersionSpecRequiresAnExplicitPeriod() {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        // A version-2 moment at minute 95 with a null period would silently lose extra-time direction.
        assertThrows(IllegalArgumentException.class, () -> new MatchMomentSpec("CTIM:VER", 0, PLAN_SEED, 2,
                95, 2, null, HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                SCORER, ASSISTER, players, null));
    }

    @Test void currentVersionRecipeJsonWithoutPeriodIsRejected() {
        var directed = director.direct(versioned(2, MatchPeriod.EXTRA_TIME_FIRST_HALF));
        String json = codec.encode(directed.recipe());
        String withoutPeriod = json.replaceAll(",?\"period\":\"[A-Z_]+\"", "");
        assertFalse(withoutPeriod.contains("\"period\""), "period should be absent from the tampered JSON");
        assertThrows(IllegalStateException.class, () -> codec.decode(withoutPeriod));
    }

    @Test void extraTimeHalvesKeepOppositeExplicitDirectionsUnderVersionTwo() {
        AnimationReplay et1 = director.direct(versioned(2, MatchPeriod.EXTRA_TIME_FIRST_HALF)).replay();
        AnimationReplay et2 = director.direct(versioned(2, MatchPeriod.EXTRA_TIME_SECOND_HALF)).replay();
        assertTrue(et1.homeAttacksRight());
        assertFalse(et2.homeAttacksRight());
        assertNotEquals(et1.homeAttacksRight(), et2.homeAttacksRight());
    }

    @Test void legacyRecipeJsonWithoutPeriodFieldStillDecodesAndReplaysToTheGolden() {
        // Recipes persisted before the explicit period model have no "period" field at all.
        var directed = director.direct(versioned(1, null));
        String json = codec.encode(directed.recipe());
        String legacyJson = json.replaceAll(",?\"period\":null", "");
        assertFalse(legacyJson.contains("\"period\""), "period field should be absent from legacy JSON");
        AnimationRecipe decoded = codec.decode(legacyJson);
        assertNull(decoded.period());
        assertEquals(GOLDEN_V1, director.replay(decoded).fingerprint());
    }
}
