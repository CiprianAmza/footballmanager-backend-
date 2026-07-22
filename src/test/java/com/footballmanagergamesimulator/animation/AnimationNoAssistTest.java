package com.footballmanagergamesimulator.animation;

import com.footballmanagergamesimulator.animation.pattern.PatternLibrary;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** With no canonical assist there can be no clean team-mate pass into the scorer. */
class AnimationNoAssistTest {
    private final AnimationDirector director = new AnimationDirector();
    private final AnimationInvariantValidator validator = new AnimationInvariantValidator(AnimationPhysicsProfile.defaults());

    @Test void directorNeverEmitsACleanPassIntoAnUnassistedScorer() {
        for (AnimationPhase phase : AnimationPhase.values())
            for (AnimationOutcome outcome : AnimationOutcome.values())
                for (int slot = 0; slot < 6; slot++) {
                    MatchMomentSpec spec = spec(phase, outcome, slot, 44, null);
                    AnimationReplay replay = director.direct(spec).replay();
                    for (AnimationEvent event : replay.events())
                        if ("PASS".equals(event.type()))
                            assertNotEquals(SCORER, event.toPlayerId(),
                                    "clean pass into unassisted scorer: " + phase + "/" + outcome + "/" + slot);
                }
    }

    @Test void assistedScorerAlwaysReceivesTheFinalCleanPass() {
        for (AnimationPhase phase : AnimationPhase.values())
            for (AnimationOutcome outcome : AnimationOutcome.values()) {
                MatchMomentSpec spec = spec(phase, outcome, 0, 30, ASSISTER);
                AnimationReplay replay = director.direct(spec).replay();
                AnimationEvent lastPass = null;
                for (AnimationEvent e : replay.events()) if ("PASS".equals(e.type())) lastPass = e;
                assertNotNull(lastPass, phase + "/" + outcome);
                assertEquals(ASSISTER, lastPass.fromPlayerId());
                assertEquals(SCORER, lastPass.toPlayerId());
            }
    }

    @Test void everyOpenPlayPatternRespectsTheInverseContractDirectly() {
        // The originally-flagged LOW_CROSS_CUTBACK, and every other pattern, must never
        // produce a clean pass into an unassisted scorer.
        AnimationPhysicsProfile profile = AnimationPhysicsProfile.defaults();
        for (PlayPattern pattern : PatternLibrary.patterns())
            for (AnimationOutcome outcome : AnimationOutcome.values())
                for (int slot = 0; slot < 4; slot++) {
                    MatchMomentSpec spec = spec(pattern.phase(), outcome, slot, 40, null);
                    if (!pattern.supports(spec)) continue;
                    Random random = new Random(AnimationSeed.derive(spec.planSeed(), spec.fixtureKey(),
                            spec.slotIndex(), spec.generatorVersion()));
                    AnimationReplay replay = new FrameCompiler(profile).compile(spec, pattern.create(spec, random), random);
                    assertTrue(validator.validate(replay, spec).isEmpty(), pattern.id() + "/" + outcome);
                    for (AnimationEvent event : replay.events())
                        if ("PASS".equals(event.type()))
                            assertNotEquals(SCORER, event.toPlayerId(), pattern.id() + " passed into unassisted scorer");
                }
    }
}
