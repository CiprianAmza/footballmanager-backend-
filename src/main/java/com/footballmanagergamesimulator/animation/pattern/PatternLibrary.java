package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.PatternId;
import com.footballmanagergamesimulator.animation.PlayPattern;
import java.util.List;

/** Frozen generator-version-1 library; list order is selection-stable. */
public final class PatternLibrary {
    private static final List<PlayPattern> PATTERNS = List.of(
            new ThroughBallPattern(), new OneTwoPattern(), new ShortPassingSequencePattern(),
            new SwitchOfPlayPattern(), new CounterAttackPattern(), new LongBallPattern(),
            new OverlapAndCrossPattern(), new LowCrossCutbackPattern(), new LongShotPattern(),
            new CornerCrossPattern(), new ShortCornerPattern(), new DirectFreeKickPattern(),
            new CrossedFreeKickPattern(), new PenaltyPattern());
    private static final PlayPattern FALLBACK = new SafeFallbackPattern();

    private PatternLibrary() { }
    public static List<PlayPattern> patterns() { return PATTERNS; }
    public static PlayPattern fallback() { return FALLBACK; }
    public static PlayPattern find(PatternId id) {
        if (id == PatternId.SAFE_FALLBACK) return FALLBACK;
        return PATTERNS.stream().filter(pattern -> pattern.id() == id).findFirst().orElse(null);
    }
}
