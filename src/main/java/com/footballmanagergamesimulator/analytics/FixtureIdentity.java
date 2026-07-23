package com.footballmanagergamesimulator.analytics;

/**
 * Canonical analytics fixture identity helpers.
 *
 * <p>Phase 0 reuses the identity that already exists in the engine rather than
 * inventing a new one: a competition fixture's stable key is
 * {@code "CTIM:" + CompetitionTeamInfoMatch.id}, the same string MatchPlan and
 * MatchEvent persist. This class only formats and parses that key; it never
 * invents a namespace the engine does not already use.
 */
public final class FixtureIdentity {

    /** Namespace for a competition fixture backed by a CompetitionTeamInfoMatch row. */
    public static final String COMPETITION_PREFIX = "CTIM:";

    private FixtureIdentity() {
    }

    /** Format the canonical key for a competition fixture row id. Mirrors {@code MatchPlanService.competitionFixtureKey}. */
    public static String competitionFixtureKey(long matchRowId) {
        return COMPETITION_PREFIX + matchRowId;
    }

    public static boolean isCompetitionKey(String fixtureKey) {
        return fixtureKey != null && fixtureKey.startsWith(COMPETITION_PREFIX);
    }

    /**
     * @return the CompetitionTeamInfoMatch row id embedded in a competition
     * fixture key, or {@code -1} if the key is null / not a competition key /
     * malformed. Never throws.
     */
    public static long matchRowId(String fixtureKey) {
        if (!isCompetitionKey(fixtureKey)) {
            return -1L;
        }
        try {
            return Long.parseLong(fixtureKey.substring(COMPETITION_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
