package com.footballmanagergamesimulator.model;

/**
 * The general director's personality (design DESIGN_START_CARIERA §5.0, decided). Only the ambitious one puts a
 * club "in conflict": his own ideas against the chairman's, with the manager caught in between.
 */
public enum DirectorPersonality {
    /** Administrative only; passes on what the chairman wants. */
    NEUTRAL("Neutral"),
    /** On the manager's side and helps him. */
    SUPPORTIVE("Supportive of the manager"),
    /** Passes on only what the chairman really wants. */
    OWNER_LOYAL("Loyal to the chairman"),
    /** Wants his own ideas applied, not the chairman's: undermines a manager who ignores him. */
    AMBITIOUS("Personal ambitions");

    private final String label;

    DirectorPersonality(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
