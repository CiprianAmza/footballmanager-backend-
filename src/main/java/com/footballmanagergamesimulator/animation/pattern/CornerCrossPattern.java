package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class CornerCrossPattern extends BasePattern {
    CornerCrossPattern() { super(PatternId.CORNER_CROSS, AnimationPhase.CORNER); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && (s.assister() != null || supportCount(s) >= 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); PitchPoint spot = new PitchPoint(99, left ? 2 : 98);
        PlayerSnapshot taker = finalPasser(s, r, Set.of(), "AMC", "MC", "ML", "MR");
        return setPiece(id(), List.of(touch(taker, spot, 0, 0), touch(s.scorer(), point(r, 89, 93, 46, 54), 4, left ? 7 : -7)), spot, 38, between(r, -1.5, 1.5));
    }
}
