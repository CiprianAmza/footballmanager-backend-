package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** In-swinging corner delivered onto the scorer in the six-yard area. */
final class CornerCrossPattern extends BasePattern {

    CornerCrossPattern() {
        super("CORNER_CROSS", AnimationPhase.CORNER);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && (spec.assister() != null || hasSupport(spec, 1));
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        boolean left = rng.nextBoolean();
        double[] spot = {99, left ? 2 : 98};
        PlayerSnapshot taker = finalPasser(spec, rng, "AMC", "MC", "ML", "MR");

        List<ChainStep> chain = new ArrayList<>();
        chain.add(step(taker, spot[0], spot[1], 0, 0));
        chain.add(step(spec.scorer(), jr(rng, 89, 93), jr(rng, 46, 54), 4,
                left ? 7 : -7));
        return setPiece(id(), chain, spot, 40, jr(rng, -1.5, 1.5));
    }
}
