package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class CounterAttackPattern extends BasePattern {
    CounterAttackPattern() { super(PatternId.COUNTER_ATTACK, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) {
        return super.supports(s) && (s.assister() == null || supportCount(s) >= 1);
    }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        if (s.assister() == null) {
            return soloRun(id(), s, List.of(
                    new PitchPoint(between(r, 50, 56), between(r, 38, 62)),
                    new PitchPoint(between(r, 72, 78), between(r, 40, 60)),
                    new PitchPoint(between(r, 85, 90), between(r, 42, 58))), between(r, -2, 2));
        }
        Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot winner = support(s, r, used, "DC", "DM", "DL", "DR"); used.add(winner.playerId());
        route.add(touch(winner, point(r, 17, 25, 36, 64), 3, 0));
        route.add(touch(s.assister(), point(r, 54, 64, 28, 72), 3, 3));
        route.add(touch(s.scorer(), point(r, 85, 90, 42, 58), 4, 2));
        return open(id(), route, between(r, -2, 2));
    }
}
