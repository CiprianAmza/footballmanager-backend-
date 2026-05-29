package com.footballmanagergamesimulator.config;

/**
 * The kind of work a single European-competition round represents. A round's
 * group-draw / qualify nature is carried as flags on {@link EuropeanStage}; the
 * knockout sub-stage (QF/SF/Final) is derived from the round's distance to the
 * final.
 */
public enum EuropeanPhase {
    /** A seeded preliminary knockout round that trims the field toward the group stage. */
    PRELIMINARY,
    /** A group-stage matchday (double round-robin). */
    GROUP,
    /** A main knockout round (Round of N … QF, SF, Final). */
    KNOCKOUT
}
