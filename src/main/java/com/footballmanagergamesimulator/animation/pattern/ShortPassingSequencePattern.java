package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class ShortPassingSequencePattern extends BasePattern {
    ShortPassingSequencePattern() { super(PatternId.SHORT_PASSING_SEQUENCE, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) {
        return super.supports(s) && (s.assister() == null || supportCount(s) >= 2);
    }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        if (s.assister() == null) {
            return soloRun(id(), s, List.of(
                    new PitchPoint(between(r, 52, 58), between(r, 40, 60)),
                    new PitchPoint(between(r, 70, 76), between(r, 42, 58)),
                    new PitchPoint(between(r, 84, 89), between(r, 43, 57))), between(r, -2, 2));
        }
        Set<Long> used = new LinkedHashSet<>();
        List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot first = support(s, r, used, "DC", "DM", "DL", "DR"); used.add(first.playerId());
        PlayerSnapshot second = support(s, r, used, "MC", "DM", "ML", "MR"); used.add(second.playerId());
        route.add(touch(first, point(r, 27, 34, 36, 64), 6, 0));
        route.add(touch(second, point(r, 45, 53, 32, 68), 5, 1));
        route.add(touch(s.assister(), point(r, 65, 73, 33, 67), 5, 2));
        route.add(touch(s.scorer(), point(r, 84, 89, 43, 57), 5, 1));
        return open(id(), route, between(r, -2, 2));
    }
}
