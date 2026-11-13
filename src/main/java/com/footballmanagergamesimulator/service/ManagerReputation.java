package com.footballmanagergamesimulator.service;

/**
 * Manager reputation is on the clubs' scale, 0–10,000 (decided 2026-11-12): an AI manager starts with his club's
 * reputation, so the coach of a top club is a top manager. (It used to be a third of the club's.)
 */
public final class ManagerReputation {

    public static final int MAX = 10_000;

    private ManagerReputation() {
    }

    /** An AI manager's reputation when he takes over a club. */
    public static int startingFor(int clubReputation) {
        return Math.max(0, Math.min(MAX, clubReputation));
    }
}
