package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.*;
import java.util.*;

public final class SafeFallbackPattern implements PlayPattern {
    @Override public PatternId id() { return PatternId.SAFE_FALLBACK; }
    @Override public AnimationPhase phase() { return AnimationPhase.OPEN_PLAY; }
    @Override public Set<AnimationOutcome> supportedOutcomes() { return EnumSet.allOf(AnimationOutcome.class); }
    @Override public boolean supports(MatchMomentSpec spec) { return true; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        List<PlayScript.Touch> route = new ArrayList<>();
        if (s.assister() != null) route.add(new PlayScript.Touch(s.assisterId(), new PitchPoint(74, 50), 7, 0));
        route.add(new PlayScript.Touch(s.scorerId(), new PitchPoint(84, 50), 6, 1));
        return new PlayScript(id(), route, null, 0, 0);
    }
}
