package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;

import java.util.List;
import java.util.Random;

/**
 * Direct free kick: the scorer strikes at goal from the dead ball. Only
 * supports moments without an assister — a direct hit has no final pass.
 */
final class DirectFreeKickPattern extends BasePattern {

    DirectFreeKickPattern() {
        super("DIRECT_FREE_KICK", AnimationPhase.FREE_KICK);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return super.supports(spec) && spec.assisterId() == null;
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        double[] spot = {jr(rng, 70, 79), jr(rng, 30, 70)};
        List<ChainStep> chain = List.of(step(spec.scorer(), spot[0], spot[1], 0, 0));
        double curve = (spot[1] > 50 ? -1 : 1) * jr(rng, 3, 6);
        return setPiece(id(), chain, spot, 50, curve);
    }
}
