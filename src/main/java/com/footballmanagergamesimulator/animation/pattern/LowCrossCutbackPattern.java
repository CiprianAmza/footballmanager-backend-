package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class LowCrossCutbackPattern extends BasePattern {
    LowCrossCutbackPattern() { super(PatternId.LOW_CROSS_CUTBACK, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot first = support(s, r, used, "MC", "AMC", "DM"); used.add(first.playerId());
        route.add(touch(first, point(r, 55, 63, 34, 66), 5, 0));
        PlayerSnapshot crosser = s.assister() != null ? s.assister() : support(s, r, used, left ? "AML" : "AMR", left ? "ML" : "MR", left ? "DL" : "DR");
        route.add(touch(crosser, new PitchPoint(between(r, 91, 94), left ? between(r, 11, 19) : between(r, 81, 89)), 4, left ? 2 : -2));
        route.add(touch(s.scorer(), point(r, 84, 88, 44, 55), 4, 1));
        return open(id(), route, between(r, -1.5, 1.5));
    }
}
