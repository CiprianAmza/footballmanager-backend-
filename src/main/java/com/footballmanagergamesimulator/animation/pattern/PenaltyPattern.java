package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationOutcome;
import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;

import java.util.List;
import java.util.Random;

/**
 * Penalty kick: run-up from the edge of the D, strike from the spot.
 * BLOCKED is not a penalty outcome and penalties carry no assist; such
 * specs fall through to the safe fallback.
 */
final class PenaltyPattern extends BasePattern {

    PenaltyPattern() {
        super("PENALTY", AnimationPhase.PENALTY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec)
                && spec.outcome() != AnimationOutcome.BLOCKED
                && spec.assisterId() == null;
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        double[] spot = {88, 50};
        List<ChainStep> chain = List.of(step(spec.scorer(), spot[0], spot[1], 0, 0));
        return setPiece(id(), chain, spot, 65, jr(rng, -1, 1));
    }
}
