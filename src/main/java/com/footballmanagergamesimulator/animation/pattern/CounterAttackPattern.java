package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class CounterAttackPattern extends BasePattern {
    CounterAttackPattern() { super(PatternId.COUNTER_ATTACK, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot winner = support(s, r, used, "DC", "DM", "DL", "DR"); used.add(winner.playerId());
        route.add(touch(winner, point(r, 17, 25, 36, 64), 3, 0));
        PlayerSnapshot runner = s.assister() != null ? s.assister() : support(s, r, used, "MC", "AMC", "ML", "MR");
        route.add(touch(runner, point(r, 54, 64, 28, 72), 3, 3));
        route.add(touch(s.scorer(), point(r, 85, 90, 42, 58), 4, 2));
        return open(id(), route, between(r, -2, 2));
    }
}
