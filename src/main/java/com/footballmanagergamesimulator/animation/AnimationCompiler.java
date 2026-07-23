package com.footballmanagergamesimulator.animation;

import java.util.Random;

/**
 * A frozen, version-pinned script compiler. Each installed generator version owns
 * one implementation; once released it is never modified, so a historical recipe
 * always regenerates byte-for-byte. New behaviour ships as a new version.
 */
public interface AnimationCompiler {
    int version();

    AnimationReplay compile(MatchMomentSpec spec, PlayScript script, Random random);
}
