package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class OverlapAndCrossPattern extends BasePattern {
    OverlapAndCrossPattern() { super(PatternId.OVERLAP_AND_CROSS, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public double weight(MatchMomentSpec s) { return "ST".equals(s.scorer().tacticalPosition()) ? 1.4 : 1.0; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot midfielder = support(s, r, used, "MC", "DM", "AMC"); used.add(midfielder.playerId());
        route.add(touch(midfielder, point(r, 49, 57, 34, 66), 5, 0));
        PlayerSnapshot crosser = s.assister() != null ? s.assister() : support(s, r, used, left ? "DL" : "DR", left ? "ML" : "MR", left ? "AML" : "AMR");
        route.add(touch(crosser, new PitchPoint(between(r, 82, 88), left ? between(r, 8, 16) : between(r, 84, 92)), 4, left ? 4 : -4));
        route.add(touch(s.scorer(), point(r, 88, 92, 45, 55), 4, left ? 7 : -7));
        return open(id(), route, between(r, -2, 2));
    }
}
