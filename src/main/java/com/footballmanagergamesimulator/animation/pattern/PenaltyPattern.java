package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class PenaltyPattern extends BasePattern {
    PenaltyPattern() { super(PatternId.PENALTY, AnimationPhase.PENALTY, EnumSet.of(AnimationOutcome.GOAL, AnimationOutcome.SAVE, AnimationOutcome.MISS)); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && s.assister() == null; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        PitchPoint spot = new PitchPoint(88, 50);
        return setPiece(id(), List.of(touch(s.scorer(), spot, 0, 0)), spot, 62, between(r, -1, 1));
    }
}
