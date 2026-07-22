package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class OverlapAndCrossPattern extends BasePattern {
    OverlapAndCrossPattern() { super(PatternId.OVERLAP_AND_CROSS, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) {
        return super.supports(s) && (s.assister() == null || supportCount(s) >= 1);
    }
    @Override public double weight(MatchMomentSpec s) { return "ST".equals(s.scorer().tacticalPosition()) ? 1.4 : 1.0; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean();
        if (s.assister() == null) {
            return soloRun(id(), s, List.of(
                    new PitchPoint(between(r, 58, 63), left ? between(r, 32, 42) : between(r, 58, 68)),
                    new PitchPoint(between(r, 85, 89), between(r, 45, 55))), between(r, -2, 2));
        }
        Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot midfielder = support(s, r, used, "MC", "DM", "AMC"); used.add(midfielder.playerId());
        route.add(touch(midfielder, point(r, 49, 57, 34, 66), 5, 0));
        // The wide crosser is the canonical assister — WBL/WBR/full-backs/wingers all deliver here.
        route.add(touch(s.assister(), new PitchPoint(between(r, 82, 88), left ? between(r, 8, 16) : between(r, 84, 92)), 4, left ? 4 : -4));
        route.add(touch(s.scorer(), point(r, 88, 92, 45, 55), 4, left ? 7 : -7));
        return open(id(), route, between(r, -2, 2));
    }
}
