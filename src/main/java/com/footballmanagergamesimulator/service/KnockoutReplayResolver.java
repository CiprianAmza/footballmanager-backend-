package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;

/** Pure reconstruction of the persisted two-leg knockout result. */
final class KnockoutReplayResolver {
    private KnockoutReplayResolver() {}

    static Result resolve(int legNumber, long tieId, long homeTeamId, long awayTeamId,
                          KnockoutPlanSplit split, int[] firstLegScores) {
        int homeScore = split.score90Home() + Math.max(0, split.etHome());
        int awayScore = split.score90Away() + Math.max(0, split.etAway());
        if (legNumber == 1 && tieId != 0) {
            return new Result(homeScore, awayScore, " (1st leg)", null, "FIRST_LEG",
                    null, null, null, null, null, null);
        }

        Integer penaltyHome = split.shootoutHome() >= 0 ? split.shootoutHome() : null;
        Integer penaltyAway = split.shootoutAway() >= 0 ? split.shootoutAway() : null;
        boolean penalties = penaltyHome != null && penaltyAway != null;
        if (legNumber == 2 && tieId != 0 && firstLegScores != null) {
            int aggregateHome = firstLegScores[1] + homeScore;
            int aggregateAway = firstLegScores[0] + awayScore;
            Long winner = penalties ? (penaltyHome > penaltyAway ? homeTeamId : awayTeamId)
                    : aggregateHome > aggregateAway ? homeTeamId
                    : aggregateAway > aggregateHome ? awayTeamId : null;
            String decidedBy = penalties ? "PENALTIES"
                    : split.etHome() >= 0 ? "EXTRA_TIME" : "AGGREGATE";
            String suffix = " (agg " + aggregateHome + "-" + aggregateAway
                    + (penalties ? ", pens " + penaltyHome + "-" + penaltyAway
                    : split.etHome() >= 0 ? ", a.e.t." : "") + ")";
            return new Result(homeScore, awayScore, suffix, winner, decidedBy,
                    penaltyHome, penaltyAway, aggregateHome, aggregateAway,
                    split.etHome() >= 0 ? split.etHome() : null,
                    split.etAway() >= 0 ? split.etAway() : null);
        }

        Long winner = penalties ? (penaltyHome > penaltyAway ? homeTeamId : awayTeamId)
                : homeScore > awayScore ? homeTeamId
                : awayScore > homeScore ? awayTeamId : null;
        String decidedBy = penalties ? "PENALTIES"
                : split.etHome() >= 0 ? "EXTRA_TIME" : "NORMAL";
        String suffix = penalties ? " (pens " + penaltyHome + "-" + penaltyAway + ")"
                : split.etHome() >= 0 ? " (a.e.t.)" : "";
        return new Result(homeScore, awayScore, suffix, winner, decidedBy,
                penaltyHome, penaltyAway, null, null,
                split.etHome() >= 0 ? split.etHome() : null,
                split.etAway() >= 0 ? split.etAway() : null);
    }

    record Result(int score1, int score2, String scoreSuffix, Long winnerTeamId, String decidedBy,
                  Integer penalty1, Integer penalty2, Integer aggregate1, Integer aggregate2,
                  Integer et1, Integer et2) {}
}
