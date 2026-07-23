package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** direct() is total: it returns a valid replay for every valid spec and accepted profile. */
class AnimationFallbackTest {
    // Spans the accepted profile domain: default, tight ball, slowest accepted, and fast well-proportioned.
    private static final AnimationPhysicsProfile[] PROFILES = {
            AnimationPhysicsProfile.defaults(),
            new AnimationPhysicsProfile(0.5, 0.2, 1.5),
            new AnimationPhysicsProfile(0.3, 0.1, 1.0),
            new AnimationPhysicsProfile(2.0, 0.6, 6.0),
            new AnimationPhysicsProfile(1.2, 0.6, 6.0)
    };

    @Test void directNeverThrowsForAnyPhaseOutcomePeriodAssistOrProfile() {
        for (AnimationPhysicsProfile profile : PROFILES) {
            AnimationDirector director = new AnimationDirector(profile);
            AnimationInvariantValidator validator = new AnimationInvariantValidator(profile);
            for (MatchPeriod period : MatchPeriod.values())
                for (AnimationPhase phase : AnimationPhase.values())
                    for (AnimationOutcome outcome : AnimationOutcome.values())
                        for (Long assist : new Long[]{ASSISTER, null})
                            for (int slot = 0; slot < 4; slot++) {
                                MatchMomentSpec spec = specPeriod(period, slot, minuteFor(period), assist);
                                MatchMomentSpec shaped = reshape(spec, phase, outcome);
                                AnimationReplay replay = assertDoesNotThrow(() -> director.direct(shaped).replay(),
                                        period + "/" + phase + "/" + outcome + "/" + assist);
                                assertTrue(validator.validate(replay, shaped).isEmpty());
                            }
        }
    }

    @Test void impossibleSpecialisedComboFallsBackButKeepsEveryCanonicalFact() {
        MatchMomentSpec spec = spec(AnimationPhase.PENALTY, AnimationOutcome.BLOCKED, 4, 63, ASSISTER);
        AnimationDirector.DirectedAnimation directed = new AnimationDirector().direct(spec);
        AnimationReplay replay = directed.replay();
        assertEquals(PatternId.SAFE_FALLBACK, replay.pattern());
        assertEquals(spec.outcome(), replay.outcome());
        assertEquals(spec.minute(), replay.minute());
        assertEquals(spec.period(), replay.period());
        assertEquals(spec.scorerId(), replay.scorerId());
        assertEquals(spec.assisterId(), replay.assisterId());
        assertEquals(PatternId.SAFE_FALLBACK, directed.recipe().pattern());
        assertTrue(new AnimationInvariantValidator(AnimationPhysicsProfile.defaults()).validate(replay, spec).isEmpty());
    }

    private static int minuteFor(MatchPeriod period) {
        return switch (period) {
            case FIRST_HALF -> 20;
            case SECOND_HALF -> 70;
            case EXTRA_TIME_FIRST_HALF -> 95;
            case EXTRA_TIME_SECOND_HALF -> 115;
        };
    }

    private static MatchMomentSpec reshape(MatchMomentSpec s, AnimationPhase phase, AnimationOutcome outcome) {
        return new MatchMomentSpec(s.fixtureKey(), s.slotIndex(), s.planSeed(), s.generatorVersion(),
                s.minute(), s.firstHalfStoppage(), s.period(), s.scoringTeamId(), s.defendingTeamId(),
                s.homeTeamId(), phase, outcome, s.scorerId(), s.assisterId(), s.playersOnPitch(), s.tacticalContext());
    }
}
