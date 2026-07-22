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

/** Ball won deep, two fast vertical passes, finish before the block sets. */
final class CounterAttackPattern extends BasePattern {

    CounterAttackPattern() {
        super("COUNTER_ATTACK", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && hasSupport(spec, spec.assister() != null ? 1 : 2);
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        Set<Long> used = new LinkedHashSet<>();
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot s1 = pickSupport(spec, rng, used, "DC", "DM", "DL", "DR");
        used.add(s1.playerId());
        chain.add(step(s1, jr(rng, 18, 26), jr(rng, 38, 62), 4, 0));

        PlayerSnapshot passer = spec.assister() != null ? spec.assister()
                : pickSupport(spec, rng, used, "MC", "AMC", "ML", "MR");
        chain.add(step(passer, jr(rng, 55, 65), jr(rng, 30, 70), 4, 2));
        chain.add(step(spec.scorer(), jr(rng, 86, 90), jr(rng, 42, 58), 4, 1));
        return openPlay(id(), chain, jr(rng, -2, 2));
    }
}
