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

/** Patient 4-pass build-up out of the back, through midfield, into the box. */
final class ShortPassingSequencePattern extends BasePattern {

    ShortPassingSequencePattern() {
        super("SHORT_PASSING_SEQUENCE", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && hasSupport(spec, 2);
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        Set<Long> used = new LinkedHashSet<>();
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot s1 = pickSupport(spec, rng, used, "DC", "DM", "DL", "DR");
        used.add(s1.playerId());
        PlayerSnapshot s2 = pickSupport(spec, rng, used, "MC", "DM", "ML", "MR");
        used.add(s2.playerId());
        chain.add(step(s1, jr(rng, 28, 36), jr(rng, 38, 62), 8, 0));
        chain.add(step(s2, jr(rng, 46, 54), jr(rng, 32, 68), 6, 1));
        PlayerSnapshot passer = finalPasser(spec, rng, "AMC", "MC", "AML", "AMR");
        if (passer != null && !used.contains(passer.playerId())) {
            chain.add(step(passer, jr(rng, 66, 74), jr(rng, 32, 68), 6, 2));
        }
        chain.add(step(spec.scorer(), jr(rng, 84, 89), jr(rng, 42, 58), 6, 1));
        return openPlay(id(), chain, jr(rng, -2, 2));
    }
}
