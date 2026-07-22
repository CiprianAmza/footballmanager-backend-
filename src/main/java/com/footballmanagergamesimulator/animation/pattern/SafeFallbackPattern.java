package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Always-valid fallback: a minimal, physically trivial move that preserves
 * every canonical datum (scorer shoots; assister — when present — plays the
 * final pass; outcome geometry handled by the compiler). Used when no
 * specialised pattern supports a spec, or when a produced animation fails
 * physics validation.
 */
public final class SafeFallbackPattern extends BasePattern {

    public static final String ID = "SAFE_FALLBACK";

    public SafeFallbackPattern() {
        super(ID, AnimationPhase.OPEN_PLAY);
    }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return true;
    }

    @Override
    public Choreography choreograph(MatchMomentSpec spec, Random rng) {
        List<ChainStep> chain = new ArrayList<>();
        if (spec.assister() != null) {
            chain.add(step(spec.assister(), jr(rng, 72, 76), jr(rng, 44, 56), 8, 0));
            chain.add(step(spec.scorer(), jr(rng, 84, 87), jr(rng, 44, 56), 6, 1));
        } else {
            chain.add(step(spec.scorer(), jr(rng, 78, 82), jr(rng, 44, 56), 14, 0));
        }
        return openPlay(ID, chain, jr(rng, -1, 1));
    }
}
