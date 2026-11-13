package com.footballmanagergamesimulator.model;

/**
 * What a player looks for in a move (design §3.6, decided 2026-11-12). It decides whether he will talk to a club
 * at all and, for MONEY, how much more he asks.
 */
public enum PlayerPersonality {
    /** Goes anywhere, for more than he earns now — the more he outclasses the club's best, the more. */
    MONEY,
    /** Only clubs at most 200 reputation below his own club. */
    FAME,
    /** Only clubs whose best three average at least 85% of his rating. */
    AMBITION;

    /** Players saved before personalities existed get a stable one from their id until the next assignment. */
    public static PlayerPersonality of(Human player) {
        if (player.getPlayerPersonality() != null) return player.getPlayerPersonality();
        return values()[(int) Math.floorMod(player.getId(), (long) values().length)];
    }
}
