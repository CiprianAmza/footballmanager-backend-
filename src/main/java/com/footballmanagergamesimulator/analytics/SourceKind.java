package com.footballmanagergamesimulator.analytics;

/**
 * Canonical execution source of an analytics fixture fact, Phase 0.
 *
 * <p>These are the real code paths that produce a committed canonical result at
 * base {@code 90cf15b}: the human instant round, AI-vs-AI batch, the interactive
 * live commit, and the Fast Forward auto-continue route (which runs the instant
 * engine while every human manager is on Always Continue). {@link #LEGACY_UNVERSIONED}
 * is the honest fallback for fixtures with no persisted provenance row — old saves
 * or matches produced before the provenance-v2 flag was on. It is never a silent
 * upgrade to a canonical kind.
 */
public enum SourceKind {
    INSTANT,
    AI,
    INTERACTIVE_LIVE,
    FAST_FORWARD,
    LEGACY_UNVERSIONED;

    /** Null/blank-safe parse used when reading a persisted row; unknown values map to legacy. */
    public static SourceKind fromStored(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY_UNVERSIONED;
        }
        try {
            return SourceKind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return LEGACY_UNVERSIONED;
        }
    }
}
