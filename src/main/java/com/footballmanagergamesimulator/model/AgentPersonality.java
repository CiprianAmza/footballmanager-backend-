package com.footballmanagergamesimulator.model;

/** What a player's agent pursues in a negotiation (design §3.2). */
public enum AgentPersonality {
    /** A long contract for the player. */
    STABILITY,
    /** Money for himself: signing bonus / commission. */
    PERSONAL_GAIN,
    /** Selling his players as often as possible: low release clauses. */
    TURNOVER
}
