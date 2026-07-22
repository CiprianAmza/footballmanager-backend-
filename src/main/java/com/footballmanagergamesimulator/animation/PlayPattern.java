package com.footballmanagergamesimulator.animation;

import java.util.Random;
import java.util.Set;

public interface PlayPattern {
    PatternId id();
    AnimationPhase phase();
    Set<AnimationOutcome> supportedOutcomes();
    boolean supports(MatchMomentSpec spec);
    default double weight(MatchMomentSpec spec) { return 1.0; }
    PlayScript create(MatchMomentSpec spec, Random random);
}
