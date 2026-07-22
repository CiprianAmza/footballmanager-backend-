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

/** Long diagonal to the far flank, then worked inside for the finish. */
final class SwitchOfPlayPattern extends BasePattern {

    SwitchOfPlayPattern() {
        super("SWITCH_OF_PLAY", AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && hasSupport(spec, spec.assister() != null ? 1 : 2);
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        boolean fromLeft = rng.nextBoolean();
        double nearY = fromLeft ? jr(rng, 18, 30) : jr(rng, 70, 82);
        double farY = fromLeft ? jr(rng, 82, 92) : jr(rng, 8, 18);

        Set<Long> used = new LinkedHashSet<>();
        List<ChainStep> chain = new ArrayList<>();
        PlayerSnapshot s1 = pickSupport(spec, rng, used, "MC", "DM", "DC");
        used.add(s1.playerId());
        chain.add(step(s1, jr(rng, 44, 52), nearY, 8, 0));

        if (spec.assister() != null) {
            PlayerSnapshot wide = pickSupport(spec, rng, used,
                    fromLeft ? "MR" : "ML", fromLeft ? "AMR" : "AML", "MC");
            if (wide != null) {
                used.add(wide.playerId());
                chain.add(step(wide, jr(rng, 64, 70), farY, 5, 8));
            }
            chain.add(step(spec.assister(), jr(rng, 76, 82),
                    fromLeft ? jr(rng, 62, 76) : jr(rng, 24, 38), 5, 2));
        } else {
            PlayerSnapshot wide = pickSupport(spec, rng, used,
                    fromLeft ? "MR" : "ML", fromLeft ? "AMR" : "AML", "MC");
            used.add(wide.playerId());
            chain.add(step(wide, jr(rng, 68, 76), farY, 5, 8));
        }
        chain.add(step(spec.scorer(), jr(rng, 86, 90), jr(rng, 43, 57), 5, 2));
        return openPlay(id(), chain, jr(rng, -2, 2));
    }
}
