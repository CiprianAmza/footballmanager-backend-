package com.footballmanagergamesimulator.compartment;

import java.util.List;

/** Immutable, domain-free snapshot of the canonical tactic axes relevant to contextual rating. */
public record TacticalContextInput(
        String mentality,
        String tempo,
        String passingType,
        String defensiveLine,
        String pressing,
        String width,
        String recovery,
        List<String> playerInstructions) {

    public TacticalContextInput {
        playerInstructions = playerInstructions == null ? List.of() : List.copyOf(playerInstructions);
    }

    /** Compatibility constructor for callers created before recovery became a tactical axis. */
    public TacticalContextInput(String mentality, String tempo, String passingType,
                                String defensiveLine, String pressing, String width,
                                List<String> playerInstructions) {
        this(mentality, tempo, passingType, defensiveLine, pressing, width, "Standard",
                playerInstructions);
    }

    public static TacticalContextInput neutral() {
        return new TacticalContextInput("Balanced", "Standard", "Normal", "Standard",
                "Normal", "Balanced", "Standard", List.of());
    }
}
