package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class ThroughBallPattern extends BasePattern {
    ThroughBallPattern() { super(PatternId.THROUGH_BALL, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s); }
    @Override public double weight(MatchMomentSpec s) { return "ST".equals(s.scorer().tacticalPosition()) ? 1.6 : 1.0; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        if (s.assister() == null) {
            return soloRun(id(), s, List.of(
                    new PitchPoint(between(r, 54, 60), between(r, 44, 56)),
                    new PitchPoint(between(r, 84, 89), between(r, 44, 56))), between(r, -2, 2));
        }
        List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot starter = support(s, r, "MC", "DM", "DC");
        if (starter != null) route.add(touch(starter, point(r, 44, 53, 35, 65), 7, 0));
        route.add(touch(s.assister(), point(r, 62, 69, 35, 65), 5, 2));
        route.add(touch(s.scorer(), point(r, 84, 89, 43, 57), 5, 1));
        return open(id(), route, between(r, -2, 2));
    }
}
