package com.footballmanagergamesimulator.model;

/** The kinds of entities a {@link HumanEntityRelation} can join. Ids are those of the matching table. */
public enum RelationParty {
    /** A player ({@code Human} of player type). */
    PLAYER,
    /** A manager ({@code Human} of manager type). */
    MANAGER,
    /** A general director / chairman ({@code Human}). */
    DIRECTOR,
    /** A player's agent ({@link Agent}). */
    AGENT,
    /** A club ({@link Team}). */
    CLUB,
    /** A pundit ({@link Analyst}): his relation with a manager sets how kindly he writes about him. */
    ANALYST,
    /** A club's chairman ({@code Human} of chairman type): his trust in the manager and in the director. */
    CHAIRMAN
}
