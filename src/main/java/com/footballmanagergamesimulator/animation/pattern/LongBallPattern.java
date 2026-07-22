package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Defender/keeper launch over the top; optional flick-on by the assister. */
final class LongBallPattern extends BasePattern {

    LongBallPattern() {
        super("LONG_BALL", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && hasSupport(spec, 1);
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot launcher = pickSupport(spec, rng, "DC", "GK", "DL", "DR", "DM");
        chain.add(step(launcher, jr(rng, 14, 24), jr(rng, 38, 62), 8, 0));
        if (spec.assister() != null) {
            chain.add(step(spec.assister(), jr(rng, 58, 66), jr(rng, 38, 62), 4, 5));
        }
        chain.add(step(spec.scorer(), jr(rng, 84, 89), jr(rng, 42, 58), 5, 4));
        return openPlay(id(), chain, jr(rng, -2, 2));
    }
}
