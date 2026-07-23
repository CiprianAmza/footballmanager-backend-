package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class LongBallPattern extends BasePattern {
    LongBallPattern() { super(PatternId.LONG_BALL, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        if (s.assister() == null) {
            return soloRun(id(), s, List.of(
                    new PitchPoint(between(r, 55, 61), between(r, 40, 60)),
                    new PitchPoint(between(r, 84, 89), between(r, 42, 58))), between(r, -2, 2));
        }
        List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot launcher = support(s, r, "DC", "GK", "DL", "DR", "DM");
        if (launcher != null) route.add(touch(launcher, point(r, 12, 23, 35, 65), 7, 0));
        route.add(touch(s.assister(), point(r, 58, 66, 35, 65), 4, 5));
        route.add(touch(s.scorer(), point(r, 84, 89, 42, 58), 4, 4));
        return open(id(), route, between(r, -2, 2));
    }
}
