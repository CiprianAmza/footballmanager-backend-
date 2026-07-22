package com.footballmanagergamesimulator.animation.v1;

import com.footballmanagergamesimulator.animation.PlayPattern;
import java.util.List;

/**
 * FROZEN generator-version-1 pattern library — the original 38e9b15 patterns,
 * preserved so historical version-1 recipes select and render identically. Do not
 * modify; ship new behaviour as a new generator version.
 */
public final class LegacyPatternLibrary {
    private static final List<PlayPattern> PATTERNS = List.of(
            new LegacyThroughBall(), new LegacyOneTwo(), new LegacyShortPassingSequence(),
            new LegacySwitchOfPlay(), new LegacyCounterAttack(), new LegacyLongBall(),
            new LegacyOverlapAndCross(), new LegacyLowCrossCutback(), new LegacyLongShot(),
            new LegacyCornerCross(), new LegacyShortCorner(), new LegacyDirectFreeKick(),
            new LegacyCrossedFreeKick(), new LegacyPenalty());
    private static final PlayPattern FALLBACK = new LegacySafeFallback();

    private LegacyPatternLibrary() { }

    public static List<PlayPattern> patterns() { return PATTERNS; }

    public static PlayPattern fallback() { return FALLBACK; }
}
