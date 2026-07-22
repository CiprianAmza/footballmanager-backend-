package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

final class SwitchOfPlayPattern extends BasePattern {
    SwitchOfPlayPattern() { super(PatternId.SWITCH_OF_PLAY, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) {
        return super.supports(s) && (s.assister() == null || supportCount(s) >= 1);
    }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean();
        if (s.assister() == null) {
            return soloRun(id(), s, List.of(
                    new PitchPoint(between(r, 56, 62), left ? between(r, 32, 42) : between(r, 58, 68)),
                    new PitchPoint(between(r, 85, 89), between(r, 44, 56))), between(r, -2, 2));
        }
        Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot first = support(s, r, used, "MC", "DM", "DC"); used.add(first.playerId());
        route.add(touch(first, new PitchPoint(between(r, 43, 50), left ? between(r, 17, 28) : between(r, 72, 83)), 7, 0));
        route.add(touch(s.assister(), new PitchPoint(between(r, 70, 79), left ? between(r, 68, 84) : between(r, 16, 32)), 5, left ? 8 : -8));
        route.add(touch(s.scorer(), point(r, 85, 90, 43, 57), 5, 2));
        return open(id(), route, between(r, -2, 2));
    }
}
