package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Corner played short, worked to the edge of the box, delivered late. */
final class ShortCornerPattern extends BasePattern {

    ShortCornerPattern() {
        super("SHORT_CORNER", AnimationPhase.CORNER);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && hasSupport(spec, spec.assister() != null ? 1 : 2);
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        boolean left = rng.nextBoolean();
        double[] spot = {99, left ? 2 : 98};
        double shortY = left ? jr(rng, 12, 20) : jr(rng, 80, 88);

        Set<Long> used = new LinkedHashSet<>();
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot taker = pickSupport(spec, rng, used, "AMC", "MC", left ? "ML" : "MR");
        used.add(taker.playerId());
        chain.add(step(taker, spot[0], spot[1], 0, 0));

        PlayerSnapshot deliverer = spec.assister() != null ? spec.assister()
                : pickSupport(spec, rng, used, "AMC", "MC", left ? "AML" : "AMR");
        chain.add(step(deliverer, jr(rng, 86, 91), shortY, 5, 1));
        chain.add(step(spec.scorer(), jr(rng, 88, 92), jr(rng, 44, 54), 4, left ? 5 : -5));
        return setPiece(id(), chain, spot, 40, jr(rng, -1.5, 1.5));
    }
}
