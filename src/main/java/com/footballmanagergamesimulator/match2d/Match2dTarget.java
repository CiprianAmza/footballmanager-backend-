package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;

import java.util.List;

/**
 * The {@code target} of a 2D request: the result the classic engine already decided, which
 * the 2D engine must reproduce exactly. {@code score} = goals in the 90 minutes;
 * {@code extraTime} = goals scored in extra time, present only when extra time is played;
 * {@code penalties} = the shootout tally, present only when there is a shootout (always
 * after extra time). All pairs are {@code [home, away]} in the orientation of the match.
 * Pure: building the target and checking a result against it do no I/O.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Match2dTarget(List<Integer> score, List<Integer> extraTime, List<Integer> penalties,
                            Integer maxTries) {

    /** The target of a match the classic engine decided as {@code split}. */
    public static Match2dTarget of(KnockoutPlanSplit split) {
        boolean extraTime = split.etHome() >= 0;
        boolean penalties = split.shootoutHome() >= 0;
        return new Match2dTarget(List.of(split.score90Home(), split.score90Away()),
                extraTime ? List.of(split.etHome(), split.etAway()) : null,
                penalties ? List.of(split.shootoutHome(), split.shootoutAway()) : null,
                null);
    }

    /**
     * Why {@code result} does not reproduce this target, or {@code null} when it does: it must
     * be usable ({@link Match2dMapper#usable}), not report the target as missed, list exactly
     * the target's regular-time goals for each side, play extra time exactly when the target
     * does and list exactly its extra-time goals, and have exactly the target's shootout
     * (none when the target has none).
     */
    public String problemWith(Match2dResult result) {
        if (result == null) return "no result";
        if (result.isFailed()) return result.error();
        if (!Match2dMapper.usable(result)) return "the goals do not add up to the score or fall outside the match";
        if (result.target() != null && Boolean.FALSE.equals(result.target().met())) {
            return "the engine reports the target as not met";
        }
        int[] regular = {Match2dMapper.regularTimeGoals(result, 0), Match2dMapper.regularTimeGoals(result, 1)};
        if (regular[0] != score.get(0) || regular[1] != score.get(1)) {
            return "90' goals " + regular[0] + "-" + regular[1] + " instead of " + score.get(0) + "-" + score.get(1);
        }
        boolean playedExtraTime = Match2dMapper.playedExtraTime(result);
        if (playedExtraTime != (extraTime != null)) {
            return extraTime != null ? "extra time was not played" : "extra time was played";
        }
        if (extraTime != null) {
            int[] et = {Match2dMapper.extraTimeGoals(result, 0), Match2dMapper.extraTimeGoals(result, 1)};
            if (et[0] != extraTime.get(0) || et[1] != extraTime.get(1)) {
                return "extra-time goals " + et[0] + "-" + et[1] + " instead of "
                        + extraTime.get(0) + "-" + extraTime.get(1);
            }
        }
        List<Integer> shootout = result.knockout() == null ? null : result.knockout().penalties();
        if (penalties == null ? shootout != null : !penalties.equals(shootout)) {
            return "shootout " + shootout + " instead of " + penalties;
        }
        return null;
    }
}
