package com.footballmanagergamesimulator.dynamics;

/**
 * Squad hierarchy tier, derived deterministically from real player data
 * (leadership, determination, rating, seniority, age, reputation).
 * There is no artificial/generated personality behind this.
 */
public enum HierarchyTier {
    LEADER,
    HIGHLY_INFLUENTIAL,
    INFLUENTIAL,
    OTHER
}
