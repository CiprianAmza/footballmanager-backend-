package com.footballmanagergamesimulator.matchplan;

/** Phase a canonical goal belongs to. Shootout penalties are NOT goals and are
 *  tracked separately on the {@link MatchPlan}, not as a phase here. */
public enum GoalPhase {
    REGULAR_TIME,
    EXTRA_TIME
}
