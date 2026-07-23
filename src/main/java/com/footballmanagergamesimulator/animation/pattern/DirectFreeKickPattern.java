package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class DirectFreeKickPattern extends BasePattern {
    DirectFreeKickPattern() { super(PatternId.DIRECT_FREE_KICK, AnimationPhase.FREE_KICK); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && s.assister() == null; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        PitchPoint spot = point(r, 69, 79, 29, 71);
        return setPiece(id(), List.of(touch(s.scorer(), spot, 0, 0)), spot, 48, (spot.y() > 50 ? -1 : 1) * between(r, 3, 6));
    }
}
