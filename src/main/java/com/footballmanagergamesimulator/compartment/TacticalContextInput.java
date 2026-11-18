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
        String inPossession,
        String timeWasting,
        String dribbling,
        String widePlay,
        String transition,
        List<String> playerInstructions) {

    /** Defaults of the team-instruction axes that only tactical combinations read. */
    public static final String DEFAULT_IN_POSSESSION = "Standard";
    public static final String DEFAULT_TIME_WASTING = "Sometimes";
    public static final String DEFAULT_DRIBBLING = "Standard";
    public static final String DEFAULT_WIDE_PLAY = "Shoot";
    public static final String DEFAULT_TRANSITION = "Balanced";

    public TacticalContextInput {
        playerInstructions = playerInstructions == null ? List.of() : List.copyOf(playerInstructions);
    }

    /** Compatibility constructor for callers created before the combination axes reached the engine. */
    public TacticalContextInput(String mentality, String tempo, String passingType,
                                String defensiveLine, String pressing, String width, String recovery,
                                List<String> playerInstructions) {
        this(mentality, tempo, passingType, defensiveLine, pressing, width, recovery,
                DEFAULT_IN_POSSESSION, DEFAULT_TIME_WASTING, DEFAULT_DRIBBLING, DEFAULT_WIDE_PLAY,
                DEFAULT_TRANSITION, playerInstructions);
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
