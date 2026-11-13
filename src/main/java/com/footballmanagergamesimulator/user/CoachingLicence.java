package com.footballmanagergamesimulator.user;

/**
 * The new manager's coaching licence (design DESIGN_START_CARIERA §3). Stored and shown for now; which league
 * needs which licence, and what happens without it, is a later step.
 */
public enum CoachingLicence {
    NONE("No licence"),
    GOALKEEPING("Goalkeeping licence"),
    C("C licence"),
    B("B licence"),
    A("A licence"),
    PRO("Pro licence");

    private final String label;

    CoachingLicence(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
