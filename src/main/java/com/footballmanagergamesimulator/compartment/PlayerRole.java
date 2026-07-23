package com.footballmanagergamesimulator.compartment;

import java.util.Arrays;
import java.util.Optional;

/** Stable typed role keys, mapped from the display names used by {@code PlayerRoleService}. */
public enum PlayerRole {
    GOALKEEPER("Goalkeeper"),
    SWEEPER_KEEPER("Sweeper Keeper"),
    CENTRAL_DEFENDER("Central Defender"),
    BALL_PLAYING_DEFENDER("Ball-Playing Defender"),
    NO_NONSENSE_DEFENDER("No-Nonsense Defender"),
    FULL_BACK("Full-Back"),
    WING_BACK("Wing-Back"),
    INVERTED_WING_BACK("Inverted Wing-Back"),
    CENTRAL_MIDFIELDER("Central Midfielder"),
    DEEP_LYING_PLAYMAKER("Deep-Lying Playmaker"),
    BALL_WINNING_MIDFIELDER("Ball-Winning Midfielder"),
    BOX_TO_BOX_MIDFIELDER("Box-to-Box Midfielder"),
    ADVANCED_PLAYMAKER("Advanced Playmaker"),
    MEZZALA("Mezzala"),
    WINGER("Winger"),
    INSIDE_FORWARD("Inside Forward"),
    WIDE_MIDFIELDER("Wide Midfielder"),
    INVERTED_WINGER("Inverted Winger"),
    ADVANCED_FORWARD("Advanced Forward"),
    POACHER("Poacher"),
    TARGET_MAN("Target Man"),
    DEEP_LYING_FORWARD("Deep-Lying Forward"),
    PRESSING_FORWARD("Pressing Forward"),
    COMPLETE_FORWARD("Complete Forward");

    private final String displayName;

    PlayerRole(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }

    public static Optional<PlayerRole> fromDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) return Optional.empty();
        return Arrays.stream(values()).filter(role -> role.displayName.equals(displayName)).findFirst();
    }
}
