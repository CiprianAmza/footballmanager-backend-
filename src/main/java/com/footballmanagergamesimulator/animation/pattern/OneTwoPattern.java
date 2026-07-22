package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class OneTwoPattern extends BasePattern {
    OneTwoPattern() { super(PatternId.ONE_TWO, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && s.assister() != null; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot starter = support(s, r, "MC", "DM");
        if (starter != null) route.add(touch(starter, point(r, 54, 61, 38, 62), 6, 0));
        double y = between(r, 42, 58);
        route.add(touch(s.scorer(), new PitchPoint(between(r, 73, 77), y), 4, 1));
        route.add(touch(s.assister(), new PitchPoint(between(r, 79, 83), y + (y > 50 ? -8 : 8)), 4, 2));
        route.add(touch(s.scorer(), point(r, 87, 90, 44, 56), 4, 2));
        return open(id(), route, between(r, -2, 2));
    }
}
