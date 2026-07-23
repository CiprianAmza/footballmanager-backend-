package com.footballmanagergamesimulator.dynamics;

/**
 * A participant's reaction to a team meeting or player conversation.
 * Mirrors the buckets the frontend already renders for legacy team talks so
 * the UI styling stays consistent.
 */
public enum DynamicsReaction {
    FIRED_UP,
    MOTIVATED,
    PLEASED,
    NEUTRAL,
    UNHAPPY,
    FURIOUS
}
