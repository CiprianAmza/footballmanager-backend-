package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Three generator versions coexist and dispatch is by exact version. Versions 1 and 2 are frozen
 * for replay; version 3 is current. Every persisted recipe must regenerate byte-identically under
 * its own version forever, so each installed version has a golden fingerprint here.
 */
class AnimationVersionTest {
    /**
     * Physics profile in force when the frozen goldens below were captured.
     *
     * <p>A replay is a pure function of (generator version, physics profile), and
     * {@link AnimationRecipe} pins the profile it was rendered with — so a persisted recipe always
     * replays under its own profile, never under whatever the ambient default happens to be. These
     * tests therefore pin the profile explicitly instead of relying on
     * {@link AnimationPhysicsProfile#defaults()}, which commit 735804f retuned from
     * {@code (0.9, 0.45, 4.0)} to {@code (0.45, 0.15, 1.5)} to slow the live presentation down.
     *
     * <p>Pinning is what keeps the golden <em>values</em> below unchanged from the day they were
     * captured, so they still prove the frozen engines are byte-for-byte frozen. Re-baselining them
     * to the retuned defaults would have made them agree with whatever the engine currently emits
     * and would have hidden the real version-2 drift that {@link #frozenVersionTwoKeepsItsExactFrozenFrameCountAndFingerprint()}
     * now guards.
     */
    private static final AnimationPhysicsProfile FROZEN_PROFILE = new AnimationPhysicsProfile(0.9, 0.45, 4.0);

    private final AnimationDirector director = new AnimationDirector(FROZEN_PROFILE);
    /** Director on the ambient runtime profile, used to prove recipe profile-pinning actually works. */
    private final AnimationDirector ambientDirector = new AnimationDirector();
    private final AnimationRecipeCodec codec = new AnimationRecipeCodec();

    /** Pre-upgrade golden fingerprint of the original version-1 replay (captured from commit 38e9b15). */
    private static final long GOLDEN_V1 = 4958526529101888831L;
    /** Version 1 is frozen at a fixed 150-frame envelope (+1 snapshot). */
    private static final int FROZEN_V1_FRAMES = 151;
    /** Golden fingerprint of the frozen version-2 replay for the same canonical facts. */
    private static final long GOLDEN_V2 = -6125445712436773644L;
    /** Version 2 is frozen exporting its whole profile-adaptive envelope (160 frames + 1 snapshot). */
    private static final int FROZEN_V2_FRAMES = 161;
    /**
     * Golden fingerprint of the current version-3 replay. Version 3 is released and frozen
     * (Codex rev. 8: "generator versions 1-3 and their budgets remain frozen"), so new behaviour
     * must ship as version 4 rather than changing this value.
     */
    private static final long GOLDEN_V3 = 1215361926874242253L;
    /** Version 3 paces dynamically: it exports its real action plus a result beat, not the envelope. */
    private static final int FROZEN_V3_FRAMES = 123;

    private static MatchMomentSpec versioned(int version, MatchPeriod period) {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        return new MatchMomentSpec("CTIM:VER", 0, PLAN_SEED, version, 30, 2, period,
                HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER, ASSISTER, players, null);
    }

    // Commit 735804f bumped the current generator from 2 to 3 (FrameCompiler.VERSION), so this
    // expectation moved 2 -> 3; versions 1 and 2 stayed installed and frozen alongside it.
    @Test void currentVersionIsThreeAndNewMomentsUseIt() {
        assertEquals(3, AnimationDirector.CURRENT_GENERATOR_VERSION);
        assertEquals(3, FrameCompiler.VERSION);
        assertEquals(2, FrameCompiler.PREVIOUS_VERSION);
        assertEquals(3, director.direct(spec()).replay().renderedWithVersion());
        assertEquals(3, ambientDirector.direct(spec()).replay().renderedWithVersion());
    }

    @Test void frozenVersionOneKeepsItsExactPreUpgradeFingerprint() {
        AnimationReplay v1 = director.direct(versioned(1, null)).replay();
        assertEquals(1, v1.renderedWithVersion());
        assertEquals(FROZEN_V1_FRAMES, v1.frames().size(), "version 1 frame count is frozen");
        assertEquals(GOLDEN_V1, v1.fingerprint());
        assertNull(v1.period(), "legacy replay carries no explicit period");
    }

    /**
     * Version 2 is a distinct frozen implementation. Its frame count is part of what is frozen:
     * commit 4b9c2a1 introduced version-3 dynamic pacing but applied the shortened export to every
     * version, cutting this replay from 161 frames to 59, and commit 9ce513b added reactive
     * defending without a version gate. Both are now scoped to {@code version >= FrameCompiler.VERSION}.
     */
    @Test void frozenVersionTwoKeepsItsExactFrozenFrameCountAndFingerprint() {
        AnimationReplay v2 = director.direct(versioned(2, MatchPeriod.FIRST_HALF)).replay();
        assertEquals(2, v2.renderedWithVersion());
        assertEquals(FROZEN_V2_FRAMES, v2.frames().size(), "version 2 frame count is frozen");
        assertEquals(GOLDEN_V2, v2.fingerprint());
        assertNotEquals(GOLDEN_V1, GOLDEN_V2);
    }

    @Test void currentVersionThreeKeepsItsExactFingerprint() {
        AnimationReplay v3 = director.direct(versioned(3, MatchPeriod.FIRST_HALF)).replay();
        assertEquals(3, v3.renderedWithVersion());
        assertEquals(FROZEN_V3_FRAMES, v3.frames().size());
        assertEquals(GOLDEN_V3, v3.fingerprint());
        assertNotEquals(GOLDEN_V2, GOLDEN_V3);
    }

    /**
     * The frozen versions always export their whole scheduling envelope, whatever the profile;
     * only the current version paces dynamically. This is the direct regression guard for the
     * commit-4b9c2a1 drift, which was profile-independent.
     */
    @Test void frozenVersionsExportTheirWholeEnvelopeUnderEveryProfile() {
        for (AnimationPhysicsProfile profile : List.of(FROZEN_PROFILE, AnimationPhysicsProfile.defaults())) {
            AnimationDirector scoped = new AnimationDirector(profile);
            AnimationReplay v1 = scoped.direct(versioned(1, null)).replay();
            assertEquals(AnimationFrameBudget.framesFor(1, profile) + 1, v1.frames().size(),
                    "version 1 must export its whole envelope under " + profile);
            AnimationReplay v2 = scoped.direct(versioned(2, MatchPeriod.FIRST_HALF)).replay();
            assertEquals(AnimationFrameBudget.framesFor(2, profile) + 1, v2.frames().size(),
                    "version 2 must export its whole envelope under " + profile);
            AnimationReplay v3 = scoped.direct(versioned(3, MatchPeriod.FIRST_HALF)).replay();
            assertTrue(v3.frames().size() <= AnimationFrameBudget.framesFor(3, profile) + 1,
                    "version 3 stays within its envelope under " + profile);
        }
    }

    @Test void allInstalledVersionsRenderValidReplays() {
        AnimationInvariantValidator validator = new AnimationInvariantValidator(FROZEN_PROFILE);
        for (MatchMomentSpec spec : List.of(versioned(1, MatchPeriod.FIRST_HALF),
                versioned(2, MatchPeriod.FIRST_HALF), versioned(3, MatchPeriod.FIRST_HALF))) {
            assertTrue(validator.validate(director.direct(spec).replay(), spec).isEmpty(),
                    "version " + spec.generatorVersion() + " must render a valid replay");
        }
        MatchMomentSpec legacy = versioned(1, null);
        assertTrue(validator.validate(director.direct(legacy).replay(), legacy).isEmpty());
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

    /**
     * The persisted-replay contract that the retuned {@link AnimationPhysicsProfile#defaults()}
     * must never break: a recipe pins its own profile, so a director running on the current ambient
     * profile still regenerates a stored v1/v2 recipe to its original golden.
     */
    @Test void storedRecipesReplayToTheirGoldenOnADirectorWithADifferentAmbientProfile() {
        assertNotEquals(FROZEN_PROFILE, AnimationPhysicsProfile.defaults(),
                "this test is only meaningful while the ambient profile differs from the frozen one");
        AnimationRecipe v1 = codec.decode(codec.encode(director.direct(versioned(1, null)).recipe()));
        assertEquals(FROZEN_PROFILE, v1.physicsProfile(), "recipe must persist its physics profile");
        assertEquals(GOLDEN_V1, ambientDirector.replay(v1).fingerprint());

        AnimationRecipe v2 = codec.decode(codec.encode(
                director.direct(versioned(2, MatchPeriod.FIRST_HALF)).recipe()));
        assertEquals(FROZEN_PROFILE, v2.physicsProfile());
        AnimationReplay replayed = ambientDirector.replay(v2);
        assertEquals(FROZEN_V2_FRAMES, replayed.frames().size());
        assertEquals(GOLDEN_V2, replayed.fingerprint());
    }

    @Test void currentVersionSpecRequiresAnExplicitPeriod() {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        // A current-version moment at minute 95 with a null period would silently lose extra-time direction.
        assertThrows(IllegalArgumentException.class, () -> new MatchMomentSpec("CTIM:VER", 0, PLAN_SEED,
                AnimationDirector.CURRENT_GENERATOR_VERSION, 95, 2, null, HOME, AWAY, HOME,
                AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER, ASSISTER, players, null));
    }

    @Test void currentVersionRecipeJsonWithoutPeriodIsRejected() {
        var directed = director.direct(versioned(AnimationDirector.CURRENT_GENERATOR_VERSION,
                MatchPeriod.EXTRA_TIME_FIRST_HALF));
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
