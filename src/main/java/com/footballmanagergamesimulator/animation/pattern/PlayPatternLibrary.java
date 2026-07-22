package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.PlayPattern;

import java.util.List;

/**
 * The version-1 pattern library. Order is significant: pattern selection
 * iterates this list in a fixed order with a seeded RNG, so reordering or
 * inserting entries changes historical selections and requires a
 * generator-version bump.
 */
public final class PlayPatternLibrary {

    private static final List<PlayPattern> STANDARD = List.of(
            new ThroughBallPattern(),
            new OneTwoPattern(),
            new ShortPassingSequencePattern(),
            new SwitchOfPlayPattern(),
            new CounterAttackPattern(),
            new LongBallPattern(),
            new OverlapAndCrossPattern(),
            new LowCrossCutbackPattern(),
            new LongShotPattern(),
            new CornerCrossPattern(),
            new ShortCornerPattern(),
            new DirectFreeKickPattern(),
            new CrossedFreeKickPattern(),
            new PenaltyPattern());

    private static final PlayPattern FALLBACK = new SafeFallbackPattern();

    private PlayPatternLibrary() {
    }

    /** Patterns participating in weighted selection (fallback excluded). */
    public static List<PlayPattern> standard() {
        return STANDARD;
    }

    public static PlayPattern fallback() {
        return FALLBACK;
    }

    /** Resolve a version-1 pattern id; null means corrupted or incompatible persisted data. */
    public static PlayPattern byId(String id) {
        if (SafeFallbackPattern.ID.equals(id)) return FALLBACK;
        for (PlayPattern p : STANDARD) if (p.id().equals(id)) return p;
        return null;
    }
}
