package com.footballmanagergamesimulator.animation;

import com.footballmanagergamesimulator.animation.pattern.PatternLibrary;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationPhysicsPropertyTest {
    private final AnimationPhysicsProfile profile = AnimationPhysicsProfile.defaults();
    private final AnimationInvariantValidator validator = new AnimationInvariantValidator(profile);

    @Test void everyPatternPreservesEverySupportedOutcomeAndInvariant() {
        List<PlayPattern> patterns = new ArrayList<>(PatternLibrary.patterns()); patterns.add(PatternLibrary.fallback());
        int checked = 0;
        for (PlayPattern pattern : patterns) for (AnimationPhase phase : AnimationPhase.values())
            for (AnimationOutcome outcome : AnimationOutcome.values()) for (Long assist : new Long[]{ASSISTER, null})
                for (int slot = 0; slot < 3; slot++) {
                    MatchMomentSpec moment = spec(phase, outcome, slot, 40, assist);
                    if (!pattern.supports(moment)) continue;
                    long seed = AnimationSeed.derive(moment.planSeed(), moment.fixtureKey(), slot, moment.generatorVersion());
                    Random random = new Random(seed);
                    AnimationReplay replay = new FrameCompiler(profile).compile(moment, pattern.create(moment, random), random);
                    List<String> errors = validator.validate(replay, moment);
                    assertTrue(errors.isEmpty(), pattern.id() + "/" + phase + "/" + outcome + "/" + assist + ": " + errors);
                    assertEquals(outcome, replay.outcome()); checked++;
                }
        assertTrue(checked > 100, "property sweep count=" + checked);
    }

    @Test void stricterConfigurablePlayerLimitsAreEnforced() {
        AnimationPhysicsProfile strict = new AnimationPhysicsProfile(0.6, 0.25, 4.0);
        MatchMomentSpec moment = spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 5, 70, ASSISTER);
        AnimationReplay replay = new AnimationDirector(strict).direct(moment).replay();
        assertTrue(new AnimationInvariantValidator(strict).validate(replay, moment).isEmpty());
    }

    @Test void directorNeverReturnsAnInvalidReplay() {
        AnimationDirector director = new AnimationDirector();
        for (AnimationPhase phase : AnimationPhase.values()) for (AnimationOutcome outcome : AnimationOutcome.values()) {
            MatchMomentSpec moment = spec(phase, outcome, 2, 75, null);
            assertTrue(validator.validate(director.direct(moment).replay(), moment).isEmpty());
        }
    }
}
