package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class CornerCrossPattern extends BasePattern {
    CornerCrossPattern() { super(PatternId.CORNER_CROSS, AnimationPhase.CORNER); }
    // The corner delivery into the scorer IS the assist, so this pattern only fits an assisted goal.
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && s.assister() != null; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); PitchPoint spot = new PitchPoint(99, left ? 2 : 98);
        return setPiece(id(), List.of(touch(s.assister(), spot, 0, 0),
                touch(s.scorer(), point(r, 89, 93, 46, 54), 4, left ? 7 : -7)), spot, 38, between(r, -1.5, 1.5));
    }
}
