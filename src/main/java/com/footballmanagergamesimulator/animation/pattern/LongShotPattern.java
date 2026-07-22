package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class LongShotPattern extends BasePattern {
    LongShotPattern() { super(PatternId.LONG_SHOT, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && (s.assister() != null || supportCount(s) >= 1); }
    @Override public double weight(MatchMomentSpec s) { return Set.of("MC", "DM", "AMC").contains(s.scorer().tacticalPosition()) ? 1.8 : 0.7; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        List<PlayScript.Touch> route = new ArrayList<>(); PlayerSnapshot start = support(s, r, "DM", "MC", "DC");
        if (start != null) route.add(touch(start, point(r, 38, 47, 34, 66), 6, 0));
        if (s.assister() != null) route.add(touch(s.assister(), point(r, 57, 65, 36, 64), 5, 1));
        route.add(touch(s.scorer(), point(r, 67, 72, 41, 59), 6, 1));
        return open(id(), route, between(r, -3, 3));
    }
}
