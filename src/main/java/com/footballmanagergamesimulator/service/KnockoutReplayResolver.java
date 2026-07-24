package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;

/** Pure reconstruction of the persisted two-leg knockout result. */
final class KnockoutReplayResolver {
    private KnockoutReplayResolver() {}

    static Result resolve(int legNumber, long tieId, long homeTeamId, long awayTeamId,
                          KnockoutPlanSplit split, int[] firstLegScores) {
        if (split == null) throw new IllegalArgumentException("split must not be null");

        if (tieId == 0) {
            if (legNumber != 0) {
                throw new IllegalArgumentException("single-leg replay requires legNumber 0");
            }
            return resolveSingleLeg(homeTeamId, awayTeamId, split);
        }
        if (tieId < 0) {
            throw new IllegalArgumentException("tieId must be non-negative");
        }
        if (legNumber == 1) {
            requireAbsentExtraTime(split, "first leg");
            return new Result(split.score90Home(), split.score90Away(), " (1st leg)", null, "FIRST_LEG",
                    null, null, null, null, null, null);
        }
        if (legNumber == 2) {
            int[] firstLeg = requireFirstLeg(firstLegScores);
            return resolveTwoLegReturn(homeTeamId, awayTeamId, split, firstLeg);
        }
        throw new IllegalArgumentException("knockout replay requires legNumber 1 or 2");
    }

    private static Result resolveTwoLegReturn(long homeTeamId, long awayTeamId,
                                              KnockoutPlanSplit split, int[] firstLeg) {
        int normalHome = firstLeg[1] + split.score90Home();
        int normalAway = firstLeg[0] + split.score90Away();
        if (normalHome != normalAway) {
            requireAbsentExtraTime(split, "decided two-leg aggregate");
            return normalResult(homeTeamId, awayTeamId, split.score90Home(), split.score90Away(),
                    " (agg " + normalHome + "-" + normalAway + ")", normalHome > normalAway ? homeTeamId : awayTeamId,
                    "AGGREGATE", normalHome, normalAway);
        }
        requireExtraTime(split, "level two-leg aggregate");
        int finalHome = split.score90Home() + split.etHome();
        int finalAway = split.score90Away() + split.etAway();
        int aggregateHome = firstLeg[1] + finalHome;
        int aggregateAway = firstLeg[0] + finalAway;
        if (aggregateHome != aggregateAway) {
            if (split.shootoutHome() >= 0) {
                throw new IllegalArgumentException("shootout is invalid after extra-time decision");
            }
            return new Result(finalHome, finalAway,
                    " (agg " + aggregateHome + "-" + aggregateAway + ", a.e.t.)",
                    aggregateHome > aggregateAway ? homeTeamId : awayTeamId, "EXTRA_TIME",
                    null, null, aggregateHome, aggregateAway, split.etHome(), split.etAway());
        }
        requireShootout(split, "level two-leg aggregate after extra-time");
        return new Result(finalHome, finalAway,
                " (agg " + aggregateHome + "-" + aggregateAway + ", pens "
                        + split.shootoutHome() + "-" + split.shootoutAway() + ")",
                split.shootoutHome() > split.shootoutAway() ? homeTeamId : awayTeamId,
                "PENALTIES", split.shootoutHome(), split.shootoutAway(),
                aggregateHome, aggregateAway, split.etHome(), split.etAway());
    }

    private static Result resolveSingleLeg(long homeTeamId, long awayTeamId, KnockoutPlanSplit split) {
        if (split.score90Home() != split.score90Away()) {
            requireAbsentExtraTime(split, "decided single-leg score");
            return normalResult(homeTeamId, awayTeamId, split.score90Home(), split.score90Away(), "",
                    split.score90Home() > split.score90Away() ? homeTeamId : awayTeamId,
                    "NORMAL", null, null);
        }
        requireExtraTime(split, "level single-leg score");
        int finalHome = split.score90Home() + split.etHome();
        int finalAway = split.score90Away() + split.etAway();
        if (finalHome != finalAway) {
            if (split.shootoutHome() >= 0) {
                throw new IllegalArgumentException("shootout is invalid after extra-time decision");
            }
            return new Result(finalHome, finalAway, " (a.e.t.)",
                    finalHome > finalAway ? homeTeamId : awayTeamId, "EXTRA_TIME",
                    null, null, null, null, split.etHome(), split.etAway());
        }
        requireShootout(split, "level single-leg score after extra-time");
        return new Result(finalHome, finalAway,
                " (pens " + split.shootoutHome() + "-" + split.shootoutAway() + ")",
                split.shootoutHome() > split.shootoutAway() ? homeTeamId : awayTeamId,
                "PENALTIES", split.shootoutHome(), split.shootoutAway(),
                null, null, split.etHome(), split.etAway());
    }

    private static Result normalResult(long homeTeamId, long awayTeamId, int homeScore, int awayScore,
                                       String suffix, long winner, String decidedBy,
                                       Integer aggregateHome, Integer aggregateAway) {
        return new Result(homeScore, awayScore, suffix, winner, decidedBy,
                null, null, aggregateHome, aggregateAway, null, null);
    }

    private static int[] requireFirstLeg(int[] scores) {
        if (scores == null) {
            throw new IllegalStateException("first leg is missing for second-leg replay");
        }
        if (scores.length != 2 || scores[0] < 0 || scores[1] < 0) {
            throw new IllegalStateException("first leg scores are invalid for second-leg replay");
        }
        return scores;
    }

    private static void requireAbsentExtraTime(KnockoutPlanSplit split, String context) {
        if (split.etHome() >= 0 || split.shootoutHome() >= 0) {
            throw new IllegalArgumentException(context + " cannot contain extra-time or shootout");
        }
    }

    private static void requireExtraTime(KnockoutPlanSplit split, String context) {
        if (split.etHome() < 0) {
            throw new IllegalArgumentException(context + " requires extra-time");
        }
    }

    private static void requireShootout(KnockoutPlanSplit split, String context) {
        if (split.shootoutHome() < 0) {
            throw new IllegalArgumentException(context + " requires decisive shootout");
        }
    }

    record Result(int score1, int score2, String scoreSuffix, Long winnerTeamId, String decidedBy,
                  Integer penalty1, Integer penalty2, Integer aggregate1, Integer aggregate2,
                  Integer et1, Integer et2) {}
}
