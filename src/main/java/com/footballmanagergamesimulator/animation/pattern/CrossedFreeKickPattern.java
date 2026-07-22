package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class CrossedFreeKickPattern extends BasePattern {
    CrossedFreeKickPattern() { super(PatternId.CROSSED_FREE_KICK, AnimationPhase.FREE_KICK); }
    // The crossed delivery is the assist; an unassisted free kick is a DIRECT_FREE_KICK instead.
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && s.assister() != null; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); PitchPoint spot = new PitchPoint(between(r, 65, 75), left ? between(r, 17, 33) : between(r, 67, 83));
        return setPiece(id(), List.of(touch(s.assister(), spot, 0, 0),
                touch(s.scorer(), point(r, 88, 92, 44, 56), 4, left ? 6 : -6)), spot, 42, between(r, -1.5, 1.5));
    }
}
