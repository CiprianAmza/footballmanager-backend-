package com.footballmanagergamesimulator.match2d;

import java.util.List;

/**
 * Pure translation between the game's knockout ties and the {@code knockout} block of the 2D
 * match engine: what to ask for ({@link #request}) and how the engine's extra time and
 * shootout decide the tie ({@link #decide}). No I/O.
 *
 * <p>Orientation: a request and its result are always in the orientation of the match played,
 * {@code [home, away]} = {@code [team1, team2]} of that match row. A first leg is stored as
 * {@code [team1, team2]} of the first leg; the second leg is hosted by the first leg's away
 * side, so its {@code aggregate} is {@code [firstLeg[1], firstLeg[0]]}.
 */
public final class Match2dKnockout {

    private Match2dKnockout() {
    }

    /**
     * The {@code knockout} field for a match of a knockout round: none for a first leg (it
     * decides nothing on its own); the first-leg goals in this match's orientation for a second
     * leg; {@code [0, 0]} for a single match, and for a second leg whose first leg is unknown
     * (the game then decides it as a single match too).
     *
     * @param firstLeg the first leg's {@code [team1, team2]} score, or {@code null}
     */
    public static Match2dEngineClient.Knockout request(int legNumber, long tieId, int[] firstLeg) {
        if (legNumber == 1 && tieId != 0) return null;
        int[] aggregate = aggregateIn(legNumber, tieId, firstLeg);
        return Match2dEngineClient.Knockout.of(aggregate[0], aggregate[1]);
    }

    /** The goals each side brings into this match, {@code [home, away]}. */
    static int[] aggregateIn(int legNumber, long tieId, int[] firstLeg) {
        if (isSecondLeg(legNumber, tieId, firstLeg)) return new int[]{firstLeg[1], firstLeg[0]};
        return new int[]{0, 0};
    }

    private static boolean isSecondLeg(int legNumber, long tieId, int[] firstLeg) {
        return legNumber == 2 && tieId != 0 && firstLeg != null;
    }

    /**
     * How the tie ends, with the same fields, suffixes and {@code decidedBy} values the classic
     * knockout rule produces for the match row ({@code 1} = home = team1, {@code 2} = away).
     * {@code score1/score2} include the extra-time goals; the shootout is apart.
     */
    public record Decision(int score90Home, int score90Away, int score1, int score2, String scoreSuffix,
                           long winnerTeamId, String decidedBy,
                           Integer penalty1, Integer penalty2,
                           Integer aggregate1, Integer aggregate2,
                           Integer et1, Integer et2) {
    }

    /** The decision, or why the engine's {@code knockout} block cannot be used. */
    public record Verdict(Decision decision, String problem) {
        static Verdict of(Decision decision) {
            return new Verdict(decision, null);
        }

        static Verdict refused(String problem) {
            return new Verdict(null, problem);
        }

        public boolean usable() {
            return decision != null;
        }
    }

    /**
     * Decides the tie from the engine's {@code knockout} block, after checking it against the
     * result and the tie: the aggregate must be the one the game expects, the 90' score and the
     * extra-time goals must match the goals, extra time and the shootout must be played exactly
     * when the tie is level, and the winner and {@code decidedBy} must follow from the scores.
     * Anything else is refused, so the classic rule decides instead.
     *
     * @param firstLeg the first leg's {@code [team1, team2]} score for a second leg, else {@code null}
     */
    public static Verdict decide(Match2dResult result, int legNumber, long tieId, int[] firstLeg,
                                 long homeTeamId, long awayTeamId) {
        if (result == null || result.knockout() == null) {
            return Verdict.refused("the 2D result has no knockout block (an older engine?)");
        }
        if (legNumber == 1 && tieId != 0) return Verdict.refused("a first leg decides no tie");
        Match2dResult.Knockout ko = result.knockout();
        boolean twoLeg = isSecondLeg(legNumber, tieId, firstLeg);
        int[] expected = aggregateIn(legNumber, tieId, firstLeg);

        int[] aggregate = pair(ko.aggregate());
        if (aggregate == null || aggregate[0] != expected[0] || aggregate[1] != expected[1]) {
            return Verdict.refused("aggregate " + ko.aggregate() + " is not the expected ["
                    + expected[0] + ", " + expected[1] + "]");
        }
        int[] score90 = pair(ko.score90());
        if (score90 == null) return Verdict.refused("score90 is missing or invalid");
        if (score90[0] != Match2dMapper.regularTimeGoals(result, 0)
                || score90[1] != Match2dMapper.regularTimeGoals(result, 1)) {
            return Verdict.refused("score90 " + ko.score90() + " does not match the regular-time goals");
        }
        boolean levelAfter90 = score90[0] + aggregate[0] == score90[1] + aggregate[1];
        boolean extraTime = Boolean.TRUE.equals(ko.extraTime());
        if (extraTime != levelAfter90) {
            return Verdict.refused("extraTime=" + ko.extraTime() + " but the tie is "
                    + (levelAfter90 ? "level" : "decided") + " after 90 minutes");
        }

        int[] et = extraTime ? pair(ko.extraTimeGoals()) : new int[]{0, 0};
        if (et == null) return Verdict.refused("extraTimeGoals are missing or invalid");
        if (!extraTime && ko.extraTimeGoals() != null) {
            int[] given = pair(ko.extraTimeGoals());
            if (given == null || given[0] != 0 || given[1] != 0) {
                return Verdict.refused("extra-time goals without extra time");
            }
        }
        if (et[0] != Match2dMapper.extraTimeGoals(result, 0) || et[1] != Match2dMapper.extraTimeGoals(result, 1)) {
            return Verdict.refused("extraTimeGoals " + ko.extraTimeGoals() + " do not match the goals after minute 90");
        }
        int[] score = pair(result.score());
        if (score == null || score[0] != score90[0] + et[0] || score[1] != score90[1] + et[1]) {
            return Verdict.refused("score " + result.score() + " is not score90 plus the extra-time goals");
        }

        int final1 = score90[0] + et[0];
        int final2 = score90[1] + et[1];
        int total1 = aggregate[0] + final1;
        int total2 = aggregate[1] + final2;
        boolean penalties = extraTime && total1 == total2;
        int[] pens = null;
        if (penalties) {
            pens = pair(ko.penalties());
            if (pens == null || pens[0] == pens[1]) {
                return Verdict.refused("the tie is level after extra time but the shootout " + ko.penalties()
                        + " has no winner");
            }
        } else if (ko.penalties() != null) {
            return Verdict.refused("a shootout although the tie was decided before it");
        }

        boolean homeWon = penalties ? pens[0] > pens[1] : total1 > total2;
        if (ko.winner() == null || ko.winner() != (homeWon ? 0 : 1)) {
            return Verdict.refused("winner " + ko.winner() + " does not follow from the scores");
        }
        String engineDecidedBy = penalties ? "penalties" : extraTime ? "extra_time" : "regular";
        if (ko.decidedBy() != null && !engineDecidedBy.equals(ko.decidedBy())) {
            return Verdict.refused("decidedBy '" + ko.decidedBy() + "' does not follow from the scores");
        }

        long winner = homeWon ? homeTeamId : awayTeamId;
        Integer penalty1 = penalties ? pens[0] : null;
        Integer penalty2 = penalties ? pens[1] : null;
        Integer et1 = extraTime ? et[0] : null;
        Integer et2 = extraTime ? et[1] : null;
        String decidedBy = penalties ? "PENALTIES" : extraTime ? "EXTRA_TIME" : twoLeg ? "AGGREGATE" : "NORMAL";
        String suffix;
        if (twoLeg) {
            suffix = " (agg " + total1 + "-" + total2
                    + (penalties ? ", pens " + penalty1 + "-" + penalty2 : extraTime ? ", a.e.t." : "") + ")";
        } else {
            suffix = penalties ? " (pens " + penalty1 + "-" + penalty2 + ")" : extraTime ? " (a.e.t.)" : "";
        }
        return Verdict.of(new Decision(score90[0], score90[1], final1, final2, suffix, winner, decidedBy,
                penalty1, penalty2, twoLeg ? total1 : null, twoLeg ? total2 : null, et1, et2));
    }

    /** A two-sided non-negative pair, or {@code null}. */
    private static int[] pair(List<Integer> values) {
        if (values == null || values.size() != 2 || values.get(0) == null || values.get(1) == null
                || values.get(0) < 0 || values.get(1) < 0) {
            return null;
        }
        return new int[]{values.get(0), values.get(1)};
    }
}
