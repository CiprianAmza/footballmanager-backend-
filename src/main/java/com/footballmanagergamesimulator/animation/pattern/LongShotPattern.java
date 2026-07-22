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

/** Build-up laid back for a strike from outside the box (~25 m out). */
final class LongShotPattern extends BasePattern {

    LongShotPattern() {
        super("LONG_SHOT", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && (spec.assister() != null || hasSupport(spec, 1));
    }

    @Override
    public double weight(MatchMomentSpec spec) {
        String pos = spec.scorer().position();
        return "MC".equals(pos) || "DM".equals(pos) || "AMC".equals(pos) ? 1.8 : 0.7;
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        Set<Long> used = new LinkedHashSet<>();
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot s1 = pickSupport(spec, rng, used, "DM", "MC", "DC");
        if (s1 != null) {
            used.add(s1.playerId());
            chain.add(step(s1, jr(rng, 38, 46), jr(rng, 35, 65), 7, 0));
        }
        if (spec.assister() != null) {
            chain.add(step(spec.assister(), jr(rng, 58, 66), jr(rng, 38, 62), 5, 1));
        }
        chain.add(step(spec.scorer(), jr(rng, 68, 72), jr(rng, 42, 58), 7, 1));
        return openPlay(id(), chain, jr(rng, -3, 3));
    }
}
