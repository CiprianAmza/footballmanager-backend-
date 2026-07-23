package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class LowCrossCutbackPattern extends BasePattern {
    LowCrossCutbackPattern() { super(PatternId.LOW_CROSS_CUTBACK, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) {
        return super.supports(s) && (s.assister() == null || supportCount(s) >= 1);
    }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean();
        if (s.assister() == null) {
            // No clean cut-back into the scorer: the scorer carries in off the flank and finishes.
            return soloRun(id(), s, List.of(
                    new PitchPoint(between(r, 58, 63), left ? between(r, 34, 44) : between(r, 56, 66)),
                    new PitchPoint(between(r, 84, 88), between(r, 44, 55))), between(r, -1.5, 1.5));
        }
        Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot first = support(s, r, used, "MC", "AMC", "DM"); used.add(first.playerId());
        route.add(touch(first, point(r, 55, 63, 34, 66), 5, 0));
        // The wide crosser is the canonical assister (WBL/WBR/wingers all deliver the cut-back).
        route.add(touch(s.assister(), new PitchPoint(between(r, 91, 94), left ? between(r, 11, 19) : between(r, 81, 89)), 4, left ? 2 : -2));
        route.add(touch(s.scorer(), point(r, 84, 88, 44, 55), 4, 1));
        return open(id(), route, between(r, -1.5, 1.5));
    }
}
