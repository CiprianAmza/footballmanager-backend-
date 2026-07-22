package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Wide free kick swung into the box; the scorer attacks the delivery. */
final class CrossedFreeKickPattern extends BasePattern {

    CrossedFreeKickPattern() {
        super("CROSSED_FREE_KICK", AnimationPhase.FREE_KICK);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && (spec.assister() != null || hasSupport(spec, 1));
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        boolean left = rng.nextBoolean();
        double[] spot = {jr(rng, 66, 76), left ? jr(rng, 18, 34) : jr(rng, 66, 82)};
        PlayerSnapshot taker = finalPasser(spec, rng, "AMC", "MC", left ? "ML" : "MR");

        List<ChainStep> chain = new ArrayList<>();
        chain.add(step(taker, spot[0], spot[1], 0, 0));
        chain.add(step(spec.scorer(), jr(rng, 88, 92), jr(rng, 44, 55), 4, left ? 6 : -6));
        return setPiece(id(), chain, spot, 45, jr(rng, -1.5, 1.5));
    }
}
