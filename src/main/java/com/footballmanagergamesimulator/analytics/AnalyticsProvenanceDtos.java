package com.footballmanagergamesimulator.analytics;

/**
 * Additive API v2 provenance envelope, expressed as immutable records (mirrors
 * the {@code EconomyDtos} record-holder precedent). None of these shapes touches
 * or replaces a v1 response.
 */
public final class AnalyticsProvenanceDtos {

    private AnalyticsProvenanceDtos() {
    }

    /**
     * The provenance envelope for one fixture.
     *
     * @param provenanceV2Enabled current flag state (so the client never mistakes
     *                            a flag-OFF legacy answer for a canonical one).
     * @param versioned           whether a persisted provenance row exists for this
     *                            fixture; false means derived legacy fallback.
     */
    public record ProvenanceEnvelope(
            String fixtureKey,
            boolean provenanceV2Enabled,
            boolean versioned,
            String sourceKind,
            String engineVersion,
            int schemaVersion,
            ReconciliationView reconciliation,
            FixtureCoordinates fixture) {
    }

    public record ReconciliationView(
            String status,
            String detail,
            Integer canonicalHome,
            Integer canonicalAway,
            Integer resultHome,
            Integer resultAway,
            Integer statsHome,
            Integer statsAway) {

        static ReconciliationView from(MatchReconciliationService.Result result) {
            return new ReconciliationView(
                    result.status().name(), result.detail(),
                    result.canonicalHome(), result.canonicalAway(),
                    result.resultHome(), result.resultAway(),
                    result.statsHome(), result.statsAway());
        }
    }

    public record FixtureCoordinates(
            long competitionId,
            int seasonNumber,
            int roundNumber,
            long homeTeamId,
            long awayTeamId) {
    }

    /** Typed error branch, aligned with {@code EconomyDtos.ApiError}. */
    public record ApiError(String code, String message) {
    }
}
