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

/** Byline run and a low cutback to the penalty spot for a first-time finish. */
final class LowCrossCutbackPattern extends BasePattern {

    LowCrossCutbackPattern() {
        super("LOW_CROSS_CUTBACK", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && hasSupport(spec, spec.assister() != null ? 1 : 2);
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        boolean left = rng.nextBoolean();
        double bylineY = left ? jr(rng, 12, 20) : jr(rng, 80, 88);

        Set<Long> used = new LinkedHashSet<>();
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot s1 = pickSupport(spec, rng, used, "MC", "AMC", "DM");
        used.add(s1.playerId());
        chain.add(step(s1, jr(rng, 56, 64), jr(rng, 35, 65), 6, 0));

        PlayerSnapshot byliner = spec.assister() != null ? spec.assister()
                : pickSupport(spec, rng, used,
                        left ? "AML" : "AMR", left ? "ML" : "MR", left ? "DL" : "DR");
        chain.add(step(byliner, jr(rng, 91, 94), bylineY, 4, 2));
        chain.add(step(spec.scorer(), jr(rng, 84, 88), jr(rng, 44, 54), 4, 1));
        return openPlay(id(), chain, jr(rng, -1.5, 1.5));
    }
}
