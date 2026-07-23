package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class ShortCornerPattern extends BasePattern {
    ShortCornerPattern() { super(PatternId.SHORT_CORNER, AnimationPhase.CORNER); }
    // A worked short corner ends with the assister's delivery into the scorer.
    @Override public boolean supports(MatchMomentSpec s) {
        return super.supports(s) && s.assister() != null && supportCount(s) >= 1;
    }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); PitchPoint spot = new PitchPoint(99, left ? 2 : 98); Set<Long> used = new LinkedHashSet<>();
        PlayerSnapshot taker = support(s, r, used, "AMC", "MC", left ? "ML" : "MR"); used.add(taker.playerId());
        List<PlayScript.Touch> route = List.of(
                touch(taker, spot, 0, 0),
                touch(s.assister(), new PitchPoint(between(r, 86, 90), left ? between(r, 12, 21) : between(r, 79, 88)), 4, 1),
                touch(s.scorer(), point(r, 88, 92, 45, 55), 4, left ? 5 : -5));
        return setPiece(id(), route, spot, 35, between(r, -1.5, 1.5));
    }
}
