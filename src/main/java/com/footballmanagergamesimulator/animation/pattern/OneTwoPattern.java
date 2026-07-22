package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Give-and-go on the edge of the box: scorer → assister → scorer. */
final class OneTwoPattern extends BasePattern {

    OneTwoPattern() {
        super("ONE_TWO", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && spec.assister() != null;
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot starter = pickSupport(spec, rng, "MC", "DM");
        double scorerY = jr(rng, 40, 60);
        if (starter != null) chain.add(step(starter, jr(rng, 54, 62), jr(rng, 38, 62), 7, 0));
        chain.add(step(spec.scorer(), jr(rng, 74, 78), scorerY, 5, 1));
        chain.add(step(spec.assister(), jr(rng, 80, 84), scorerY + (scorerY > 50 ? -8 : 8), 4, 2));
        chain.add(step(spec.scorer(), jr(rng, 87, 91), jr(rng, 44, 56), 5, 2));
        return openPlay(id(), chain, jr(rng, -2, 2));
    }
}
