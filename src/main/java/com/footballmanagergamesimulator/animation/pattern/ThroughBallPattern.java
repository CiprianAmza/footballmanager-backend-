package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Vertical ball in behind the defensive line; the scorer runs onto it. */
final class ThroughBallPattern extends BasePattern {

    ThroughBallPattern() {
        super("THROUGH_BALL", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && (spec.assister() != null || hasSupport(spec, 1));
    }

    @Override
    public double weight(MatchMomentSpec spec) {
        String pos = spec.scorer().position();
        return "ST".equals(pos) || "AMC".equals(pos) ? 1.6 : 1.0;
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot starter = pickSupport(spec, rng, "MC", "DM", "DC");
        if (starter != null) chain.add(step(starter, jr(rng, 46, 56), jr(rng, 38, 62), 8, 0));
        if (spec.assister() != null) {
            chain.add(step(spec.assister(), jr(rng, 62, 70), jr(rng, 38, 62), 6, 2));
        }
        chain.add(step(spec.scorer(), jr(rng, 85, 90), jr(rng, 43, 57), 6, 1));
        return openPlay(id(), chain, jr(rng, -2, 2));
    }
}
