package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class ShortCornerPattern extends BasePattern {
    ShortCornerPattern() { super(PatternId.SHORT_CORNER, AnimationPhase.CORNER); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); PitchPoint spot = new PitchPoint(99, left ? 2 : 98); Set<Long> used = new LinkedHashSet<>();
        PlayerSnapshot taker = support(s, r, used, "AMC", "MC", left ? "ML" : "MR"); used.add(taker.playerId());
        PlayerSnapshot deliverer = s.assister() != null ? s.assister() : support(s, r, used, "AMC", "MC", left ? "AML" : "AMR");
        List<PlayScript.Touch> route = List.of(touch(taker, spot, 0, 0), touch(deliverer, new PitchPoint(between(r, 86, 90), left ? between(r, 12, 21) : between(r, 79, 88)), 4, 1), touch(s.scorer(), point(r, 88, 92, 45, 55), 4, left ? 5 : -5));
        return setPiece(id(), route, spot, 35, between(r, -1.5, 1.5));
    }
}
