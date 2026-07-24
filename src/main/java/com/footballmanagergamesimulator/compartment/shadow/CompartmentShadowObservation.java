package com.footballmanagergamesimulator.compartment.shadow;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;

import java.util.Objects;

public record CompartmentShadowObservation(
        String fixtureKey,
        long homeTeamId,
        long awayTeamId,
        int legacyHomeScore,
        int legacyAwayScore,
        LegacyResult legacyResult,
        CanonicalMatchEvaluation canonicalEvaluation,
        long canonicalDurationNanos) {
    public CompartmentShadowObservation {
        if (fixtureKey == null || fixtureKey.isBlank()) throw new IllegalArgumentException("fixtureKey must not be blank");
        if (homeTeamId <= 0 || awayTeamId <= 0) throw new IllegalArgumentException("team ids must be positive");
        if (legacyHomeScore < 0 || legacyAwayScore < 0) throw new IllegalArgumentException("legacy scores must be non-negative");
        legacyResult = Objects.requireNonNull(legacyResult, "legacyResult");
        canonicalEvaluation = Objects.requireNonNull(canonicalEvaluation, "canonicalEvaluation");
        if (canonicalDurationNanos < 0) throw new IllegalArgumentException("duration must be non-negative");
    }

    public enum LegacyResult {
        HOME_WIN,
        DRAW,
        AWAY_WIN
    }
}
