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

/** Full-back overlap down the flank, deep cross to the arriving scorer. */
final class OverlapAndCrossPattern extends BasePattern {

    OverlapAndCrossPattern() {
        super("OVERLAP_AND_CROSS", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && hasSupport(spec, spec.assister() != null ? 1 : 2);
    }

    @Override
    public double weight(MatchMomentSpec spec) {
        return "ST".equals(spec.scorer().position()) ? 1.4 : 1.0;
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        boolean left = rng.nextBoolean();
        double wingY = left ? jr(rng, 8, 16) : jr(rng, 84, 92);

        Set<Long> used = new LinkedHashSet<>();
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot s1 = pickSupport(spec, rng, used, "MC", "DM", "AMC");
        used.add(s1.playerId());
        chain.add(step(s1, jr(rng, 50, 58), jr(rng, 35, 65), 6, 0));

        PlayerSnapshot crosser = spec.assister() != null ? spec.assister()
                : pickSupport(spec, rng, used,
                        left ? "DL" : "DR", left ? "ML" : "MR", left ? "AML" : "AMR");
        chain.add(step(crosser, jr(rng, 82, 88), wingY, 5, 3));
        chain.add(step(spec.scorer(), jr(rng, 88, 92), jr(rng, 45, 55), 5, 7));
        return openPlay(id(), chain, jr(rng, -2, 2));
    }
}
